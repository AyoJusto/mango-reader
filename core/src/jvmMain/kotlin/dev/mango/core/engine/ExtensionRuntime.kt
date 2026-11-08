package dev.mango.core.engine

import dev.mango.core.domain.ChallengeRequiredException
import dev.mango.core.domain.SourceImageRequest
import io.ktor.http.HttpHeaders
import java.util.logging.Level
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject

class ExtensionCallException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Network-layer failure from dispatch; the message always starts with [SENTINEL] for fallback detection. */
class ExtensionNetworkException(message: String, cause: Throwable? = null) :
    Exception(if (message.startsWith(SENTINEL)) message else "$SENTINEL $message", cause) {
    companion object {
        const val SENTINEL = "[network]"
    }
}

/** Runs a bundle in a fresh GraalJS context per call; the Application proxy is the only capability granted. */
class ExtensionRuntime(
    bundleJs: String,
    private val host: ApplicationHost = ApplicationHost(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // Sharing an Engine grants no capabilities; the per-call Context stays the sandbox boundary.
    private val engine: Engine = newExtensionEngine()
    private val bundleSource: Source =
        Source.newBuilder("js", bundleJs, "bundle.js").cached(true).buildLiteral()

    suspend fun <T> withExtension(block: suspend (ExtensionHandle) -> T): T =
        withContext(dispatcher) {
            // Lets the nested runBlocking in dispatch adopt this call's Job so cancellation propagates.
            val callJob = coroutineContext[Job]
            newExtensionContext(engine).use { context ->
                val application = host.applicationProxyFor(context, callJob)
                context.getBindings("js").putMember("Application", application)
                // atob decodes to ISO-8859-1 (browser semantics), unlike Application.base64Decode's UTF-8.
                context.getBindings("js").putMember(
                    "atob",
                    ProxyExecutable { args ->
                        String(java.util.Base64.getDecoder().decode(args[0].asString()), Charsets.ISO_8859_1)
                    },
                )
                try {
                    context.eval(bundleSource)
                } catch (e: PolyglotException) {
                    throw ExtensionCallException("bundle failed to evaluate: ${e.message}", e)
                }
                val source = context.getBindings("js").getMember("source")
                    ?: throw ExtensionCallException("bundle did not define global 'source'")
                block(ExtensionHandle(context, source, application))
            }
        }
}

// Grants nothing beyond defaults: no host access, IO, native, threads, process or class lookup.
/** The engine every [ExtensionRuntime] shares across its per-call contexts. */
internal fun newExtensionEngine(): Engine =
    Engine.newBuilder()
        .option("engine.WarnInterpreterOnly", "false")
        .build()

internal fun newExtensionContext(engine: Engine? = null): Context {
    val builder = Context.newBuilder("js")
    // engine.* options are rejected on a context that was given an explicit engine.
    if (engine != null) builder.engine(engine) else builder.option("engine.WarnInterpreterOnly", "false")
    return builder.build()
}

/** A live extension inside one context. Valid only within the withExtension block. */
class ExtensionHandle internal constructor(
    private val context: Context,
    val source: Value,
    private val application: ApplicationHost.ApplicationProxy,
) {
    private val log = java.util.logging.Logger.getLogger(ExtensionHandle::class.java.name)

    val registeredInterceptors: Int get() = application.interceptors.size

    fun extension(sourceId: String): Value =
        source.getMember(sourceId)?.takeIf { it.hasMembers() }
            ?: throw ExtensionCallException("bundle exposes no extension named '$sourceId'")

    fun invokeAwait(target: Value, method: String, vararg args: Any?): Value {
        if (!target.canInvokeMember(method)) {
            throw ExtensionCallException("extension has no method '$method'")
        }
        val result = try {
            target.invokeMember(method, *args)
        } catch (e: PolyglotException) {
            if (e.isHostException) {
                val hostException = e.asHostException()
                if (hostException is CancellationException) throw hostException
                if (hostException is ExtensionNetworkException) throw hostException
            }
            if (e.isGuestException) cloudflareChallengeIn(e.guestObject)?.let { throw it }
            throw ExtensionCallException("$method failed: ${e.message}", e)
        }
        return awaitIfPromise(result, method)
    }

    /** Like [invokeAwait], returning JSON ("null" for undefined). */
    fun invokeAwaitJson(target: Value, method: String, vararg args: Any?): String {
        val settled = invokeAwait(target, method, *args)
        val serialized = context.eval("js", "JSON").invokeMember("stringify", settled)
        return if (serialized.isNull) "null" else serialized.asString()
    }

    /** Runs the request interceptors over each image request; a failing item degrades to its input request. */
    fun runRequestInterceptors(requests: List<SourceImageRequest>): List<SourceImageRequest> =
        requests.map { request ->
            try {
                interceptOne(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log(Level.WARNING, "interceptor degraded to input request for ${request.url}", e)
                request
            }
        }

    private fun interceptOne(request: SourceImageRequest): SourceImageRequest {
        val syntheticRequest = ProxyObject.fromMap(
            mapOf(
                "url" to request.url,
                "headers" to ProxyObject.fromMap(request.headers.mapKeys { (name, _) -> name.lowercase() }),
            )
        )
        val settled = awaitPromise(context, application.interceptRequest(syntheticRequest), "interceptRequest")
        val serialized = context.eval("js", "JSON").invokeMember("stringify", settled)
        val result = Json.parseToJsonElement(if (serialized.isNull) "null" else serialized.asString()).jsonObject
        val url = (result["url"] as? JsonPrimitive)?.contentOrNull ?: request.url
        val headers = (result["headers"] as? JsonObject).orEmpty()
            .mapNotNull { (name, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { name to it } }
            .toMap()
        return SourceImageRequest(url = url, headers = foldCookiesIntoHeaders(headers, result["cookies"] as? JsonArray))
    }

    private fun foldCookiesIntoHeaders(headers: Map<String, String>, cookies: JsonArray?): Map<String, String> {
        if (cookies.isNullOrEmpty()) return headers
        val cookiePairs = linkedMapOf<String, String>()
        val existingCookieKey = headers.keys.firstOrNull { it.equals(HttpHeaders.Cookie, ignoreCase = true) }
        existingCookieKey?.let { key ->
            headers.getValue(key).split(';').forEach { part ->
                val trimmed = part.trim()
                val separator = trimmed.indexOf('=')
                if (separator >= 0) cookiePairs[trimmed.substring(0, separator)] = trimmed.substring(separator + 1)
            }
        }
        cookies.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            val value = (obj["value"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            cookiePairs[name] = value
        }
        val result = headers.filterKeys { !it.equals(HttpHeaders.Cookie, ignoreCase = true) }.toMutableMap()
        result[HttpHeaders.Cookie] = cookiePairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
        return result
    }

    private fun awaitIfPromise(value: Value, what: String): Value = awaitPromise(context, value, what)
}

/** Settles a possible promise synchronously; only valid at the outermost host->guest call. */
internal fun awaitPromise(context: Context, value: Value, what: String): Value {
    val isThenable = value.hasMembers() && value.canInvokeMember("then")
    if (!isThenable) return value
    var fulfilled: Value? = null
    var rejected: Value? = null
    var settled = false
    value.invokeMember(
        "then",
        ProxyExecutable { args -> fulfilled = args.getOrNull(0); settled = true; null },
        ProxyExecutable { args -> rejected = args.getOrNull(0); settled = true; null },
    )
    // Pending here never settles (graal drains microtasks on outermost return); nested use must chain natively.
    if (!settled) throw ExtensionCallException("$what returned a promise that never settled")
    rejected?.let {
        // Recover the original host exception type so callers can tell network/cancellation from bundle failure.
        hostExceptionIn<CancellationException>(it)?.let { ce -> throw ce }
        hostExceptionIn<ExtensionNetworkException>(it)?.let { ne -> throw ne }
        cloudflareChallengeIn(it)?.let { cf -> throw cf }
        val description = describePromiseError(it)
        // Fallback when the exception round-trip fails: network messages start with the sentinel.
        if (description.contains(ExtensionNetworkException.SENTINEL)) {
            throw ExtensionNetworkException(description)
        }
        throw ExtensionCallException("$what rejected: $description")
    }
    return fulfilled ?: context.eval("js", "undefined")
}

internal fun describePromiseError(error: Value): String =
    error.getMember("stack")?.takeIf { it.isString }?.asString() ?: error.toString()

/** Returns the host exception [T] wrapped by a JS rejection value, or null. */
internal inline fun <reified T : Throwable> hostExceptionIn(value: Value): T? {
    if (!value.isException) return null
    return try {
        value.throwException()
        null
    } catch (t: Throwable) {
        // Broad catch is deliberate: a probe must swallow unrelated exceptions so the caller's handling still runs.
        when {
            t is T -> t
            t is PolyglotException && t.isHostException -> t.asHostException() as? T
            else -> null
        }
    }
}

/** Builds the domain exception for the bundle's Cloudflare challenge error shape, or null for other errors. */
internal fun cloudflareChallengeIn(error: Value?): ChallengeRequiredException? {
    if (error == null || !error.hasMembers() || !error.hasMember("type")) return null
    val type = error.getMember("type")?.takeIf { it.isString }?.asString()
    if (type != "cloudflareError") return null
    val url = error.getMember("resolutionRequest")
        ?.takeIf { it.hasMembers() }
        ?.getMember("url")
        ?.takeIf { it.isString }
        ?.asString()
    return ChallengeRequiredException(sourceId = null, url = url)
}
