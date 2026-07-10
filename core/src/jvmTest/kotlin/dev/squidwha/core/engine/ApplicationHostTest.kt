package dev.squidwha.core.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headers
import kotlinx.coroutines.runBlocking
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives [ApplicationHost] straight through its ProxyObject/Value surface (no authored JS
 * text — every call goes through the polyglot API directly, same as CLAUDE.md requires of
 * the production bindings themselves).
 */
class ApplicationHostTest {
    private fun newContext(): Context =
        Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build()

    @Test
    fun twoSetCookieHeadersSurviveAsDistinctCookies() = runBlocking {
        val engine = MockEngine {
            respond(
                "{}".encodeToByteArray(),
                HttpStatusCode.OK,
                headers {
                    append(HttpHeaders.ContentType, "application/json")
                    // an Expires value containing a comma is exactly what breaks naive
                    // comma-joining of multi-value headers
                    append(HttpHeaders.SetCookie, "session=abc123; Path=/; Expires=Wed, 09 Jun 2027 10:18:14 GMT")
                    append(HttpHeaders.SetCookie, "theme=dark; Path=/")
                },
            )
        }
        val host = ApplicationHost(http = HttpClient(engine))
        newContext().use { context ->
            val app = host.applicationProxyFor(context)
            val scheduleRequest = app.getMember("scheduleRequest") as ProxyExecutable
            val request = ProxyObject.fromMap(mapOf("url" to "https://example.com/api", "method" to "GET"))

            val rawResult = scheduleRequest.execute(context.asValue(request))
            val result = awaitPromise(context, context.asValue(rawResult), "scheduleRequest")
            val response = result.getArrayElement(0)
            val cookies = response.getMember("cookies")

            assertEquals(2, cookies.arraySize, "expected both Set-Cookie headers preserved distinctly")
            val byName = (0 until cookies.arraySize).associate { i ->
                val cookie = cookies.getArrayElement(i)
                cookie.getMember("name").asString() to cookie.getMember("value").asString()
            }
            assertEquals("abc123", byName["session"])
            assertEquals("dark", byName["theme"])
        }
    }

    @Test
    fun requestInterceptorReachesOutgoingRequestAndResponseInterceptorSeesResponse() = runBlocking {
        var capturedHeader: String? = null
        val engine = MockEngine { request ->
            capturedHeader = request.headers["X-Injected"]
            respond("ok".encodeToByteArray(), HttpStatusCode.OK)
        }
        val host = ApplicationHost(http = HttpClient(engine))
        newContext().use { context ->
            val app = host.applicationProxyFor(context)
            var responseStatusSeenByInterceptor: Int? = null

            // an interceptor "target" implemented purely in Kotlin, exactly like a bundle's
            // `_`-derived interceptor class would look from the host's point of view
            val interceptorTarget = ProxyObject.fromMap(
                mapOf(
                    "interceptRequest" to ProxyExecutable { args ->
                        val req = args[0]
                        ProxyObject.fromMap(
                            mapOf(
                                "url" to req.getMember("url").asString(),
                                "method" to req.getMember("method").asString(),
                                "headers" to ProxyObject.fromMap(mapOf("X-Injected" to "yes")),
                            )
                        )
                    },
                    "interceptResponse" to ProxyExecutable { args ->
                        responseStatusSeenByInterceptor = args[1].getMember("status").asInt()
                        args[2]
                    },
                )
            )

            val selector = app.getMember("Selector") as ProxyExecutable
            val requestSelectorId =
                selector.execute(context.asValue(interceptorTarget), context.asValue("interceptRequest"))
            val responseSelectorId =
                selector.execute(context.asValue(interceptorTarget), context.asValue("interceptResponse"))

            val registerInterceptor = app.getMember("registerInterceptor") as ProxyExecutable
            registerInterceptor.execute(
                context.asValue("test-interceptor"),
                context.asValue(requestSelectorId),
                context.asValue(responseSelectorId),
            )

            val scheduleRequest = app.getMember("scheduleRequest") as ProxyExecutable
            val request = ProxyObject.fromMap(mapOf("url" to "https://example.com/x", "method" to "GET"))
            val rawResult = scheduleRequest.execute(context.asValue(request))
            awaitPromise(context, context.asValue(rawResult), "scheduleRequest")

            assertEquals("yes", capturedHeader, "request interceptor's header did not reach the outgoing request")
            assertEquals(200, responseStatusSeenByInterceptor, "response interceptor never saw the response")
        }
    }

    @Test
    fun decodeHtmlEntitiesHandlesXmlEntitiesAndNumericForms() {
        assertEquals("<b>&\"'</b>", decodeHtmlEntities("&lt;b&gt;&amp;&quot;&apos;&lt;/b&gt;"))
        assertEquals("AB", decodeHtmlEntities("&#65;&#66;"))
        assertEquals("AB", decodeHtmlEntities("&#x41;&#x42;"))
        // astral code points must not be truncated to 16 bits
        assertEquals("📖", decodeHtmlEntities("&#x1F4D6;"))
        assertEquals("📖", decodeHtmlEntities("&#128214;"))
        // malformed/out-of-range entities stay literal instead of throwing
        assertEquals("&#99999999999;", decodeHtmlEntities("&#99999999999;"))
        assertEquals("&#x110000;", decodeHtmlEntities("&#x110000;"))
    }

    @Test
    fun base64RoundTrips() {
        val encoded = base64Encode("hello, squidwha")
        assertEquals("hello, squidwha", base64Decode(encoded))
    }

    @Test
    fun md5MatchesKnownVector() {
        assertEquals("5eb63bbbe01eeed093cb22bb8f5acdc3", md5Hash("hello world"))
    }
}
