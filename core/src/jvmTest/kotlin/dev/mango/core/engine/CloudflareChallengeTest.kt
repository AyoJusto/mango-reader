package dev.mango.core.engine

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Drives the Cloudflare-challenge detection path through fake ProxyObjects (no authored
 * JS text), same style as ApplicationHostTest.
 */
class CloudflareChallengeTest {
    private fun newContext(): Context =
        Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build()

    @Test
    fun rejectedPromiseCarryingCloudflareErrorSurfacesAsNamedException() {
        newContext().use { context ->
            val challengeUrl = "https://example.com/cf-challenge"
            val errorObject = ProxyObject.fromMap(
                mapOf(
                    "type" to "cloudflareError",
                    "resolutionRequest" to ProxyObject.fromMap(mapOf("url" to challengeUrl)),
                )
            )
            // a fake extension whose method returns a promise that rejects with the
            // Cloudflare-challenge error shape
            val fakeExtension = ProxyObject.fromMap(
                mapOf(
                    "getSearchResults" to ProxyExecutable {
                        context.eval("js", "Promise").invokeMember("reject", context.asValue(errorObject))
                    },
                )
            )

            val rawResult = context.asValue(fakeExtension).invokeMember("getSearchResults")
            val exception = assertFailsWith<CloudflareChallengeException> {
                awaitPromise(context, rawResult, "getSearchResults")
            }
            assertEquals(challengeUrl, exception.url)
        }
    }

    @Test
    fun nonCloudflareRejectionStillSurfacesAsExtensionCallException() {
        newContext().use { context ->
            val fakeExtension = ProxyObject.fromMap(
                mapOf(
                    "getSearchResults" to ProxyExecutable {
                        val ordinaryError = context.eval("js", "new Error('boom')")
                        context.eval("js", "Promise").invokeMember("reject", ordinaryError)
                    },
                )
            )

            val rawResult = context.asValue(fakeExtension).invokeMember("getSearchResults")
            assertFailsWith<ExtensionCallException> {
                awaitPromise(context, rawResult, "getSearchResults")
            }
        }
    }
}
