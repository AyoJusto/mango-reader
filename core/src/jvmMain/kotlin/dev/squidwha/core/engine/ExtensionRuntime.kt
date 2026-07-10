package dev.squidwha.core.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyExecutable

class ExtensionCallException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Whatever produces a live [ExtensionHandle] for one call: a native 0.9 bundle
 * ([ExtensionRuntime]) or a legacy 0.8 source through the compat wrapper
 * ([Compat08Runtime]). Lets [PaperbackExtension]'s normalization logic stay identical
 * regardless of which bundle generation it's driving.
 */
interface ExtensionRuntimeSource {
    suspend fun <T> withExtension(block: suspend (ExtensionHandle) -> T): T
}

/**
 * Runs a verified Paperback 0.9 bundle in a fresh GraalJS context: create, bind the
 * Application proxy, evaluate the bundle, run the block, close. One context per call
 * (see PLANNING.md section 6); durable extension state lives in [ApplicationHost].
 *
 * Sandbox: no host access, no IO, no class lookup. The Application proxy is the only
 * capability in the context.
 */
class ExtensionRuntime(
    private val bundleJs: String,
    private val host: ApplicationHost = ApplicationHost(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ExtensionRuntimeSource {
    override suspend fun <T> withExtension(block: suspend (ExtensionHandle) -> T): T =
        withContext(dispatcher) {
            Context.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build().use { context ->
                    val application = host.applicationProxyFor(context)
                    context.getBindings("js").putMember("Application", application)
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
        // No canInvokeMember() pre-check: the 0.8 compat wrapper (vendored, not ours to
        // edit) exposes its Extension as a `new Proxy(target, {get, has})` with no
        // `ownKeys` trap, and Graal derives canInvokeMember/hasMember from ownKeys alone
        // — it reports false even though invokeMember resolves correctly through the
        // Proxy's `get` trap. A truly-missing method still surfaces clearly below.
        val result = try {
            target.invokeMember(method, *args)
        } catch (e: PolyglotException) {
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
 * result back out synchronously. Graal drains the microtask queue whenever a JS call returns
 * control to the host, at every nesting level (not just the outermost one) — proven by the
 * fact this same pattern already resolves multi-step async chains nested many calls deep
 * (search -> getCandidates -> refreshCandidateCache -> Promise.all(...)). All host bindings
 * are synchronous, so a promise still pending after that drain would never settle — surface
 * it instead of hanging.
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
    rejected?.let { throw ExtensionCallException("$what rejected: ${describePromiseError(it)}") }
    return fulfilled ?: context.eval("js", "undefined")
}

internal fun describePromiseError(error: Value): String =
    error.getMember("stack")?.takeIf { it.isString }?.asString() ?: error.toString()
