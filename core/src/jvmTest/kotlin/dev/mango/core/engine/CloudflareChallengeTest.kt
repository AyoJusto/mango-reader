package dev.mango.core.engine

import dev.mango.core.domain.ChallengeRequiredException
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
            val fakeExtension = ProxyObject.fromMap(
                mapOf(
                    "getSearchResults" to ProxyExecutable {
                        context.eval("js", "Promise").invokeMember("reject", context.asValue(errorObject))
                    },
                )
            )

            val rawResult = context.asValue(fakeExtension).invokeMember("getSearchResults")
            val exception = assertFailsWith<ChallengeRequiredException> {
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
