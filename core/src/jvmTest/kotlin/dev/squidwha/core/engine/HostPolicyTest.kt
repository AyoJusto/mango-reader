package dev.squidwha.core.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The host-side policy floor from M1.5: per-host rate limiting, request timeout, and the
 * error taxonomy (ExtensionNetworkException, CancellationException passthrough) at the
 * engine boundary. See docs/application-surface.md's "Host policy" section.
 */
class HostPolicyTest {
    private fun newContext(): Context =
        Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build()

    @Test
    fun consecutiveRequestsToSameHostAreRateLimited() = runBlocking {
        val timestamps = mutableListOf<Long>()
        val engine = MockEngine {
            timestamps += System.currentTimeMillis()
            respond("ok".encodeToByteArray(), HttpStatusCode.OK)
        }
        val host = ApplicationHost(http = HttpClient(engine), minRequestIntervalMillis = 200)
        newContext().use { context ->
            val app = host.applicationProxyFor(context)
            val scheduleRequest = app.getMember("scheduleRequest") as ProxyExecutable
            val request = ProxyObject.fromMap(mapOf("url" to "https://example.com/api", "method" to "GET"))
            repeat(2) {
                val raw = scheduleRequest.execute(context.asValue(request))
                awaitPromise(context, context.asValue(raw), "scheduleRequest")
            }
        }
        val gap = timestamps[1] - timestamps[0]
        assertTrue(gap >= 180, "expected >=180ms between same-host dispatches (tolerance for timer slack), was ${gap}ms")
    }

    @Test
    fun slowResponseSurfacesAsExtensionNetworkException() = runBlocking {
        val engine = MockEngine {
            delay(500)
            respond("ok".encodeToByteArray(), HttpStatusCode.OK)
        }
        val host = ApplicationHost(http = HttpClient(engine), requestTimeoutMillis = 100)
        newContext().use { context ->
            val app = host.applicationProxyFor(context)
            val scheduleRequest = app.getMember("scheduleRequest") as ProxyExecutable
            val request = ProxyObject.fromMap(mapOf("url" to "https://example.com/slow", "method" to "GET"))
            assertFailsWith<ExtensionNetworkException> {
                val raw = scheduleRequest.execute(context.asValue(request))
                awaitPromise(context, context.asValue(raw), "scheduleRequest")
            }
        }
        Unit
    }

    @Test
    fun ioExceptionSurfacesAsExtensionNetworkExceptionThroughARealBundleSearch() = runBlocking {
        val engine = MockEngine { throw IOException("connection reset") }
        val host = ApplicationHost(http = HttpClient(engine))
        val extension = PaperbackExtension("FlameComics", flameComicsBundle, host)
        assertFailsWith<ExtensionNetworkException> { extension.search("solo") }
        Unit
    }

    @Test
    fun cancellingCallerDuringABlockedDispatchSurfacesCancellationPromptlyNotWrapped() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        val engine = MockEngine {
            requestStarted.complete(Unit)
            awaitCancellation()
        }
        val host = ApplicationHost(http = HttpClient(engine))
        val extension = PaperbackExtension("FlameComics", flameComicsBundle, host)

        val deferred = async(Dispatchers.Default) { extension.search("solo") }
        requestStarted.await()
        deferred.cancel()

        // bounded: if cancellation never reached the nested runBlocking's blocking HTTP
        // call, this would hang instead of completing
        withTimeout(1_000) {
            assertFailsWith<CancellationException> { deferred.await() }
        }
        Unit
    }
}
