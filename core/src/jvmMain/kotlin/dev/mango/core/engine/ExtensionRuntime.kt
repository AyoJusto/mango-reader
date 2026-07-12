package dev.mango.core.engine

import dev.mango.core.domain.ChallengeRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyExecutable

class ExtensionCallException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Raised by [ApplicationHost.scheduleRequest]'s dispatch for network-layer failures: an
 * IOException from the underlying HTTP client, or the host's own request timeout. Distinct
 * from [ExtensionCallException] so callers can retry/report network trouble differently from
 * a bundle bug. The message always starts with [SENTINEL] — see [hostExceptionIn] for why.
 */
class ExtensionNetworkException(message: String, cause: Throwable? = null) :
    Exception(if (message.startsWith(SENTINEL)) message else "$SENTINEL $message", cause) {
    companion object {
        const val SENTINEL = "[network]"
    }
}

/**
 * Runs a verified Paperback 0.9 bundle in a fresh GraalJS context: create, bind the
 * Application proxy, evaluate the bundle, run the block, close. One context per call;
 * durable extension state lives in [ApplicationHost].
 *
 * Sandbox: no host access, no IO, no class lookup. The Application proxy is the only
 * capability in the context.
 */
class ExtensionRuntime(
    private val bundleJs: String,
    private val host: ApplicationHost = ApplicationHost(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun <T> withExtension(block: suspend (ExtensionHandle) -> T): T =
        withContext(dispatcher) {
            // handed to ApplicationHost so its dispatch's nested runBlocking (needed because
            // it's invoked synchronously from a JS callback, see scheduleRequest) can adopt
            // this call's Job as parent, letting outer cancellation reach an in-flight
            // blocking HTTP call instead of being invisible to it
            val callJob = coroutineContext[Job]
            newExtensionContext().use { context ->
                    val application = host.applicationProxyFor(context, callJob)
                    context.getBindings("js").putMember("Application", application)
                    // browser global probed at module top level by the bundles' inlined
                    // HTML parser (its Buffer fallback crashes GraalJS). Browser semantics:
                    // base64 -> binary string, one char per byte, hence ISO-8859-1 — NOT
                    // the UTF-8 convention of Application.base64Decode.
                    context.getBindings("js").putMember(
                        "atob",
                        ProxyExecutable { args ->
                            String(java.util.Base64.getDecoder().decode(args[0].asString()), Charsets.ISO_8859_1)
                        },
                    )
                    try {
                        context.eval(Source.newBuilder("js", bundleJs, "bundle.js").buildLiteral())
                    } catch (e: PolyglotException) {
                        throw ExtensionCallException("bundle failed to evaluate: ${e.message}", e)
                    }
                    val source = context.getBindings("js").getMember("source")
                        ?: throw ExtensionCallException("bundle did not define global 'source'")
                    block(ExtensionHandle(context, source, application))
                }
        }
}

/**
 * The one place the guest context is configured. Deliberately grants NOTHING beyond
 * defaults: no host access, no IO, no native, no threads, no process, no class lookup.
 * SandboxTest pins these denials; any new option here must keep it green.
 */
internal fun newExtensionContext(): Context =
    Context.newBuilder("js")
        .option("engine.WarnInterpreterOnly", "false")
        .build()

/** A live extension inside one context. Valid only within the withExtension block. */
class ExtensionHandle internal constructor(
    private val context: Context,
    val source: Value,
    private val application: ApplicationHost.ApplicationProxy,
) {
    val registeredInterceptors: Int get() = application.interceptors.size

    fun extension(sourceId: String): Value =
        source.getMember(sourceId)?.takeIf { it.hasMembers() }
            ?: throw ExtensionCallException("bundle exposes no extension named '$sourceId'")

    /** Invokes a method, awaits its promise if it returns one, returns the settled Value. */
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

    /** Like [invokeAwait] but returns the settled value as a JSON string ("null" for undefined). */
    fun invokeAwaitJson(target: Value, method: String, vararg args: Any?): String {
        val settled = invokeAwait(target, method, *args)
        val serialized = context.eval("js", "JSON").invokeMember("stringify", settled)
        return if (serialized.isNull) "null" else serialized.asString()
    }

    private fun awaitIfPromise(value: Value, what: String): Value = awaitPromise(context, value, what)
}

/**
 * Settles a value that may be a JS promise, by registering then-callbacks and reading the
 * result back out synchronously. Only valid at the outermost host->guest boundary — see the
 * NOTE inside for why this cannot be used from within a running JS call.
 */
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
    // graal drains the microtask queue when the outermost JS frame exits, and all
    // host bindings are synchronous, so a promise that is still pending here would
    // never settle — surface it instead of hanging. NOTE: this only works for calls made
    // directly from Kotlin at the outermost host->guest boundary; anything invoked from
    // *inside* a running JS call (e.g. an interceptor invoked from scheduleRequest, itself
    // called from deep in a bundle's async chain) must compose as a native JS promise
    // chain instead (see ApplicationHost.scheduleRequest) — this pattern does not settle
    // when nested.
    if (!settled) throw ExtensionCallException("$what returned a promise that never settled")
    rejected?.let {
        // a host exception thrown from inside a .then() callback deep in scheduleRequest's
        // chain (e.g. dispatch's CancellationException or ExtensionNetworkException) becomes
        // this rejection's reason; recover the original type before falling back to a
        // generic ExtensionCallException, so callers can tell network/cancellation apart
        // from an ordinary bundle failure
        hostExceptionIn<CancellationException>(it)?.let { ce -> throw ce }
        hostExceptionIn<ExtensionNetworkException>(it)?.let { ne -> throw ne }
        cloudflareChallengeIn(it)?.let { cf -> throw cf }
        val description = describePromiseError(it)
        // fallback if the round-trip above ever stops working for some rejection shapes:
        // ExtensionNetworkException's message always starts with this sentinel
        if (description.contains(ExtensionNetworkException.SENTINEL)) {
            throw ExtensionNetworkException(description)
        }
        throw ExtensionCallException("$what rejected: $description")
    }
    return fulfilled ?: context.eval("js", "undefined")
}

internal fun describePromiseError(error: Value): String =
    error.getMember("stack")?.takeIf { it.isString }?.asString() ?: error.toString()

/**
 * Recovers the original host-thrown [T] from a JS rejection/error value that wraps it.
 *
 * Empirically verified against a live IOException/CancellationException round-trip through
 * [ApplicationHost.scheduleRequest]'s promise chain: `Value.throwException()` never
 * rethrows a wrapped host exception bare — it always surfaces as a [PolyglotException] with
 * `isHostException() == true`, whose `asHostException()` is the original Kotlin exception.
 * The `catch (t: T)` branch below is defensive only (documented Graal behavior for
 * `throwException()` allows a bare rethrow in principle); it has never fired in testing.
 * Returns null for any value that isn't an exception, or doesn't wrap a [T].
 */
internal inline fun <reified T : Throwable> hostExceptionIn(value: Value): T? {
    if (!value.isException) return null
    return try {
        value.throwException()
        null // throwException() always throws when isException is true; unreachable
    } catch (t: Throwable) {
        // broad catch is deliberate: this is a probe ("does this rejection wrap a T?"), not
        // a handler — anything that isn't a T (a guest error rethrown as-is, or some other
        // host exception entirely) must be swallowed here so the caller's own generic
        // handling of the rejection Value still runs, instead of this probe's unrelated
        // exception replacing it
        when {
            t is T -> t
            t is PolyglotException && t.isHostException -> t.asHostException() as? T
            else -> null
        }
    }
}

/**
 * Recognizes the in-bundle Cloudflare-challenge error shape (`type === "cloudflareError"`,
 * `resolutionRequest.url`) and builds the domain-level exception for it. Returns null for any
 * other error value so ordinary failures fall through to [ExtensionCallException] unchanged.
 * sourceId is left null: the runtime only sees a bundle-wide context, not which named export
 * within it made the call — PaperbackExtension/callers that know the sourceId attach it.
 */
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
