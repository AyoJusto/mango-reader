package dev.squidwha.core.engine

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.delay

/**
 * Kotlin side of the Paperback 0.9 `Application` surface. Holds the state that must
 * outlive the short-lived JS engine instances (extension key-value state), and the
 * host functions the JS prelude wraps. Everything an extension can reach goes through
 * here; nothing else is bound into the engine.
 */
class ApplicationHost(
    private val userAgent: String =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Paperback/0.9",
) {
    private val state = mutableMapOf<String, Any?>()

    fun bindTo(quickJs: QuickJs) {
        quickJs.define("__host") {
            function("getState") { args -> state[args[0] as String] }
            function("setState") { args ->
                // Paperback signature is setState(value, key)
                state[args[1] as String] = args[0]
                null
            }
            function("getDefaultUserAgent") { userAgent }
            asyncFunction("sleep") { args ->
                // ponytail: assuming seconds, matching @paperback/types; revisit if rate limits look 1000x off
                delay(((args[0] as Number).toDouble() * 1000).toLong())
                null
            }
            asyncFunction("scheduleRequest") {
                throw UnsupportedOperationException("Application.scheduleRequest is not implemented yet (M0.4)")
            }
        }
    }
}
