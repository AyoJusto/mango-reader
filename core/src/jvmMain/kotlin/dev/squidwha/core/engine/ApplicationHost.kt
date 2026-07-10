package dev.squidwha.core.engine

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpHeaders
import io.ktor.client.call.body
import kotlinx.coroutines.delay

/**
 * Kotlin side of the Paperback 0.9 `Application` surface. Holds what must outlive the
 * short-lived JS engine instances (extension key-value state) and the host functions
 * the JS prelude wraps. Everything an extension can reach goes through here; nothing
 * else is bound into the engine.
 */
// Extensions' scrapers are written against what sites serve to Paperback on iOS, so the
// default UA is a plain mobile Safari one (the official polyfills randomize a mobile UA;
// nothing advertises Paperback). Injectable because some sources may need a specific UA.
class ApplicationHost(
    private val http: HttpClient? = null,
    private val userAgent: String =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
    private val onResponse: ((url: String, status: Int, body: ByteArray) -> Unit)? = null,
) {
    // shared across concurrent engine instances; bindings run on dispatcher threads
    private val state = java.util.Collections.synchronizedMap(mutableMapOf<String, Any?>())

    fun bindTo(quickJs: QuickJs) {
        quickJs.define("__host") {
            function("getState") { args -> state[args[0] as String] }
            function("setState") { args ->
                // Paperback signature is setState(value, key)
                state[args[1] as String] = args[0]
                null
            }
            function("getDefaultUserAgent") { userAgent }
            function("arrayBufferToUTF8String") { args -> (args[0] as ByteArray).decodeToString() }
            asyncFunction("sleep") { args ->
                // ponytail: assuming seconds, matching @paperback/types; revisit if rate limits look 1000x off
                delay(((args[0] as Number).toDouble() * 1000).toLong())
                null
            }
            asyncFunction("scheduleRequest") { args ->
                val client = http
                    ?: throw UnsupportedOperationException("this ApplicationHost has no HTTP client")
                val request = args[0] as? Map<*, *>
                    ?: throw IllegalArgumentException("scheduleRequest expects a request object")
                val requestedUrl = request["url"] as? String
                    ?: throw IllegalArgumentException("request has no url")
                val response = execute(client, request)
                val bytes = response.body<ByteArray>()
                // record/replay is keyed by the URL the extension asked for, not the
                // post-redirect one, so replay lookups always hit
                onResponse?.invoke(requestedUrl, response.status.value, bytes)
                listOf(
                    mapOf(
                        "url" to response.call.request.url.toString(),
                        "status" to response.status.value,
                        "headers" to response.headers.entries()
                            .associate { it.key to it.value.joinToString(", ") },
                    ),
                    bytes,
                )
            }
        }
    }

    private suspend fun execute(client: HttpClient, request: Map<*, *>): HttpResponse {
        val url = request["url"] as? String
            ?: throw IllegalArgumentException("request has no url")
        return client.request(url) {
            method = HttpMethod.parse((request["method"] as? String ?: "GET").uppercase())
            (request["headers"] as? Map<*, *>)?.forEach { (k, v) ->
                header(k.toString(), v.toString())
            }
            if (headers[HttpHeaders.UserAgent] == null) header(HttpHeaders.UserAgent, userAgent)
            (request["cookies"] as? List<*>)?.takeIf { it.isNotEmpty() }?.let { cookies ->
                val pairs = cookies.map { cookie ->
                    val c = cookie as? Map<*, *>
                        ?: throw IllegalArgumentException("unsupported cookie shape: $cookie")
                    "${c["name"]}=${c["value"]}"
                }
                header(HttpHeaders.Cookie, pairs.joinToString("; "))
            }
            when (val data = request["data"]) {
                null -> {}
                is String -> setBody(data)
                is ByteArray -> setBody(data)
                // fail loudly instead of sending an empty body; add JSON-object
                // bodies when a real source needs them (M1 shake-out)
                else -> throw IllegalArgumentException(
                    "unsupported request data type: ${data::class.simpleName}"
                )
            }
        }
    }
}
