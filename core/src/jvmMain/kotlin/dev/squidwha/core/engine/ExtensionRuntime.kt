package dev.squidwha.core.engine

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Runs a verified Paperback 0.9 bundle in a fresh QuickJS engine: create, bind the
 * Application surface, evaluate the bundle, run the block, close. One engine per
 * call (see PLANNING.md section 6); durable extension state lives in [ApplicationHost].
 */
class ExtensionRuntime(
    private val bundleJs: String,
    private val host: ApplicationHost = ApplicationHost(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun <T> withExtension(block: suspend (QuickJs) -> T): T {
        val quickJs = QuickJs.create(dispatcher)
        try {
            host.bindTo(quickJs)
            quickJs.evaluate<Any?>(APPLICATION_PRELUDE)
            quickJs.evaluate<Any?>(bundleJs)
            return block(quickJs)
        } finally {
            quickJs.close()
        }
    }
}
