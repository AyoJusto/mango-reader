package dev.squidwha.core.engine

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject

/**
 * The Paperback 0.9 `Application` surface, implemented entirely in Kotlin and bound
 * into each GraalJS context as a ProxyObject. Holds what must outlive the short-lived
 * contexts (extension key-value state, serialized as JSON strings). Everything an
 * extension can reach goes through here; unknown members throw named errors.
 *
 * INVARIANT: all policy (rate limits, allowlists, timeouts) is enforced in these
 * Kotlin implementations. There is no JS layer that could be bypassed.
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
    // shared across contexts and threads; values stored as JSON strings because
    // polyglot Values die with their context
    private val state = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())

    fun applicationProxyFor(context: Context): ApplicationProxy = ApplicationProxy(context)

    inner class ApplicationProxy internal constructor(private val context: Context) : ProxyObject {
        private val selectors = mutableMapOf<String, Pair<Value, String>>()
        private var nextSelectorId = 1

        /** id -> (requestSelectorId, responseSelectorId); invocation lands in M1.2 */
        val interceptors = mutableListOf<Triple<String, String, String>>()

        private val json: Value get() = context.eval("js", "JSON")

        private val members: Map<String, Any?> = mapOf(
            "isResourceLimited" to false,
            "filterAdultTitles" to false,
            "filterMatureTitles" to false,
            "getDefaultUserAgent" to ProxyExecutable { userAgent },
            "getState" to ProxyExecutable { args ->
                state[args[0].asString()]?.let { json.invokeMember("parse", it) }
            },
            "setState" to ProxyExecutable { args ->
                // Paperback signature is setState(value, key)
                val serialized = json.invokeMember("stringify", args[0])
                if (serialized.isNull) state.remove(args[1].asString())
                else state[args[1].asString()] = serialized.asString()
                null
            },
            "sleep" to ProxyExecutable { args ->
                // ponytail: assuming seconds, matching @paperback/types; revisit if rate limits look 1000x off
                Thread.sleep((args[0].asDouble() * 1000).toLong())
                null
            },
            "arrayBufferToUTF8String" to ProxyExecutable { args ->
                bytesOf(args[0]).decodeToString()
            },
            "Selector" to ProxyExecutable { args ->
                val id = "sel-${nextSelectorId++}"
                selectors[id] = args[0] to args[1].asString()
                id
            },
            "SelectorRegistry" to ProxyObject.fromMap(
                mapOf(
                    "selector" to ProxyExecutable { args ->
                        val (target, method) = selectors[args[0].asString()]
                            ?: throw IllegalArgumentException("unknown selector ${args[0]}")
                        ProxyExecutable { callArgs -> target.invokeMember(method, *callArgs) }
                    },
                )
            ),
            "registerInterceptor" to ProxyExecutable { args ->
                interceptors +=
                    Triple(args[0].asString(), args[1].asString(), args[2].asString())
                null
            },
            "unregisterInterceptor" to ProxyExecutable { args ->
                val id = args[0].asString()
                interceptors.removeAll { it.first == id }
                null
            },
            "scheduleRequest" to ProxyExecutable { args -> scheduleRequest(args[0]) },
        )

        override fun getMemberKeys(): Any = members.keys.toList()
        override fun putMember(key: String, value: Value?) {
            throw UnsupportedOperationException("Application.$key is read-only")
        }

        // report benign engine probes as absent; claim every other member so that a
        // shim gap surfaces as a named error from getMember, never a silent undefined
        override fun hasMember(key: String): Boolean = key != "then" && key != "toJSON"

        override fun getMember(key: String): Any? =
            if (members.containsKey(key)) members[key]
            else throw UnsupportedOperationException("Application.$key is not implemented")

        private fun scheduleRequest(request: Value): ProxyArray {
            val client = http
                ?: throw UnsupportedOperationException("this ApplicationHost has no HTTP client")
            val requestedUrl = request.getMember("url")?.takeIf { it.isString }?.asString()
                ?: throw IllegalArgumentException("request has no url")
            // blocking is fine: each context owns its thread for the duration of a call
            val (response, bytes) = runBlocking {
                val r = execute(client, request, requestedUrl)
                r to r.body<ByteArray>()
            }
            // record/replay is keyed by the URL the extension asked for, not the
            // post-redirect one, so replay lookups always hit
            onResponse?.invoke(requestedUrl, response.status.value, bytes)
            val responseObject = ProxyObject.fromMap(
                mapOf(
                    "url" to response.call.request.url.toString(),
                    "status" to response.status.value,
                    // ponytail: multi-value headers comma-joined; Set-Cookie handling is M1.2
                    "headers" to ProxyObject.fromMap(
                        response.headers.entries()
                            .associate { it.key to it.value.joinToString(", ") }
                    ),
                )
            )
            return ProxyArray.fromArray(responseObject, bytes)
        }
    }

    private fun bytesOf(value: Value): ByteArray = when {
        value.isHostObject -> value.asHostObject()
        value.hasBufferElements() -> ByteArray(value.bufferSize.toInt()) { value.readBufferByte(it.toLong()) }
        else -> throw IllegalArgumentException("expected a buffer, got $value")
    }

    private suspend fun execute(
        client: HttpClient,
        request: Value,
        url: String,
    ): HttpResponse = client.request(url) {
        val methodName = request.getMember("method")?.takeIf { it.isString }?.asString() ?: "GET"
        method = HttpMethod.parse(methodName.uppercase())
        request.getMember("headers")?.takeIf { it.hasMembers() }?.let { h ->
            h.memberKeys.forEach { key -> header(key, h.getMember(key).asString()) }
        }
        if (headers[HttpHeaders.UserAgent] == null) header(HttpHeaders.UserAgent, userAgent)
        request.getMember("cookies")?.takeIf { it.hasArrayElements() && it.arraySize > 0 }?.let { cookies ->
            val pairs = (0 until cookies.arraySize).map { i ->
                val c = cookies.getArrayElement(i)
                "${c.getMember("name").asString()}=${c.getMember("value").asString()}"
            }
            header(HttpHeaders.Cookie, pairs.joinToString("; "))
        }
        request.getMember("data")?.takeIf { !it.isNull }?.let { data ->
            when {
                data.isString -> setBody(data.asString())
                data.isHostObject -> setBody(data.asHostObject<ByteArray>())
                data.hasBufferElements() -> setBody(bytesOf(data))
                // fail loudly instead of sending an empty body; add JSON-object
                // bodies when a real source needs them (M1 shake-out)
                else -> throw IllegalArgumentException("unsupported request data type: $data")
            }
        }
    }
}
