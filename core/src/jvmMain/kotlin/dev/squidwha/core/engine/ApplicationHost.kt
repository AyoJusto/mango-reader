package dev.squidwha.core.engine

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.parseServerSetCookieHeader
import kotlinx.coroutines.runBlocking
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import java.security.MessageDigest
import java.util.Base64

/**
 * The Paperback 0.9 `Application` surface, implemented entirely in Kotlin and bound
 * into each GraalJS context as a ProxyObject. Holds what must outlive the short-lived
 * contexts (extension key-value state, serialized as JSON strings). Everything an
 * extension can reach goes through here; unknown members throw named errors.
 *
 * See docs/application-surface.md for the full member-by-member audit.
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
    private val secureState = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())

    fun applicationProxyFor(context: Context): ApplicationProxy = ApplicationProxy(context)

    inner class ApplicationProxy internal constructor(private val context: Context) : ProxyObject {
        private val selectors = mutableMapOf<String, Pair<Value, String>>()
        private var nextSelectorId = 1
        private val discoverSections = mutableMapOf<String, Value>()
        private var redirectHandlerSelectorId: String? = null

        /** id -> (requestSelectorId, responseSelectorId), invoked by [scheduleRequest] in order. */
        val interceptors = mutableListOf<Triple<String, String, String>>()

        private val json: Value get() = context.eval("js", "JSON")
        private val dateCtor: Value get() = context.eval("js", "Date")
        private val promiseCtor: Value get() = context.eval("js", "Promise")

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
            "getSecureState" to ProxyExecutable { args ->
                secureState[args[0].asString()]?.let { json.invokeMember("parse", it) }
            },
            "setSecureState" to ProxyExecutable { args ->
                val serialized = json.invokeMember("stringify", args[0])
                if (serialized.isNull) secureState.remove(args[1].asString())
                else secureState[args[1].asString()] = serialized.asString()
                null
            },
            "resetAllState" to ProxyExecutable {
                state.clear()
                secureState.clear()
                null
            },
            "sleep" to ProxyExecutable { args ->
                // ponytail: assuming seconds, matching @paperback/types; revisit if rate limits look 1000x off
                Thread.sleep((args[0].asDouble() * 1000).toLong())
                null
            },
            "arrayBufferToUTF8String" to ProxyExecutable { args -> bytesOf(args[0]).decodeToString() },
            "arrayBufferToASCIIString" to ProxyExecutable { args ->
                String(bytesOf(args[0]), Charsets.US_ASCII)
            },
            "arrayBufferToUTF16String" to ProxyExecutable { args ->
                String(bytesOf(args[0]), Charsets.UTF_16LE)
            },
            "decodeHTMLEntities" to ProxyExecutable { args -> decodeHtmlEntities(args[0].asString()) },
            "base64Encode" to ProxyExecutable { args -> base64Encode(args[0].asString()) },
            "base64Decode" to ProxyExecutable { args -> base64Decode(args[0].asString()) },
            "crypto_md5Hash" to ProxyExecutable { args -> md5Hash(args[0].asString()) },
            "formDidChange" to ProxyExecutable { null },
            "setRedirectHandler" to ProxyExecutable { args ->
                redirectHandlerSelectorId = args.getOrNull(0)?.takeIf { it.isString }?.asString()
                null
            },
            "registerDiscoverSection" to ProxyExecutable { args ->
                val section = args[0]
                val id = section.getMember("id")?.takeIf { it.isString }?.asString()
                    ?: throw IllegalArgumentException("discover section has no id")
                discoverSections[id] = section
                null
            },
            "unregisterDiscoverSection" to ProxyExecutable { args ->
                discoverSections.remove(args[0].asString())
                null
            },
            "registeredDiscoverSections" to ProxyExecutable {
                ProxyArray.fromList(discoverSections.values.toList())
            },
            "invalidateDiscoverSections" to ProxyExecutable { null },
            "executeInWebView" to ProxyExecutable {
                throw UnsupportedOperationException("executeInWebView is not supported")
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

        /**
         * Runs interceptors then dispatches the HTTP request, all composed as a single
         * native JS promise chain (never synchronously read from Kotlin mid-chain).
         *
         * This has to be a real promise chain and not Kotlin code blocking on `.then()`
         * callbacks: graal only auto-drains the microtask queue on return from the single
         * outermost host->guest call (see [awaitPromise]). `scheduleRequest` is invoked
         * from deep inside a bundle's own async call graph (search -> getCandidates ->
         * ... -> scheduleRequest), so anything Kotlin tried to await synchronously here
         * would simply never settle. A genuine `.then()` chain has no such problem: JS's
         * own engine drives it to completion as part of resolving the outermost call,
         * exactly like it already drives the bundle's own internal awaits.
         */
        private fun scheduleRequest(requestArg: Value): Value {
            val client = http
                ?: throw UnsupportedOperationException("this ApplicationHost has no HTTP client")

            // request interceptors run first, in registration order; each may return a
            // (possibly promised) modified request — url/headers/cookies — which is what
            // actually gets sent
            var chain = promiseCtor.invokeMember("resolve", requestArg)
            for ((_, requestSelectorId, _) in interceptors) {
                val (target, method) = selectors[requestSelectorId]
                    ?: throw IllegalArgumentException("unknown selector $requestSelectorId")
                chain = chain.invokeMember("then", ProxyExecutable { args -> target.invokeMember(method, args[0]) })
            }

            // dispatch runs as a .then() callback: by the time JS's engine invokes it, the
            // request-interceptor chain above has already fully settled, so the blocking
            // HTTP call below can run synchronously (each context owns its thread anyway)
            var bundle = chain.invokeMember("then", ProxyExecutable { args -> dispatch(client, args[0]) })

            // response interceptors run in registration order, each transforming the
            // response data the extension ultimately sees
            for ((_, _, responseSelectorId) in interceptors) {
                val (target, method) = selectors[responseSelectorId]
                    ?: throw IllegalArgumentException("unknown selector $responseSelectorId")
                bundle = bundle.invokeMember("then", ProxyExecutable { args ->
                    val request = args[0].getMember("request")
                    val response = args[0].getMember("response")
                    val data = args[0].getMember("data")
                    promiseCtor.invokeMember("resolve", target.invokeMember(method, request, response, data))
                        .invokeMember(
                            "then",
                            ProxyExecutable { dataArgs ->
                                ProxyObject.fromMap(
                                    mapOf("request" to request, "response" to response, "data" to dataArgs[0])
                                )
                            },
                        )
                })
            }

            return bundle.invokeMember("then", ProxyExecutable { args ->
                ProxyArray.fromArray(args[0].getMember("response"), args[0].getMember("data"))
            })
        }

        /** The actual (blocking) HTTP call, plus building the response/cookies JS sees. */
        private fun dispatch(client: HttpClient, request: Value): ProxyObject {
            val requestedUrl = request.getMember("url")?.takeIf { it.isString }?.asString()
                ?: throw IllegalArgumentException("request has no url")

            // blocking is fine: each context owns its thread for the duration of a call
            val (response, bytes) = runBlocking {
                val r = execute(client, request, requestedUrl)
                r to r.body<ByteArray>()
            }
            // record/replay is keyed by the URL the extension asked for (post-interceptor,
            // since that's what actually goes over the wire), not the post-redirect one
            onResponse?.invoke(requestedUrl, response.status.value, bytes)

            val cookies = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                .map { parseServerSetCookieHeader(it) }
            val responseObject = ProxyObject.fromMap(
                mapOf(
                    "url" to response.call.request.url.toString(),
                    "status" to response.status.value,
                    // ponytail: multi-value headers comma-joined except Set-Cookie, which
                    // lives in the dedicated `cookies` field the bundle's cookie
                    // interceptor actually reads
                    // keys lowercased: bundles do exact lookups like headers["cf-mitigated"],
                    // and iOS/HTTP2 give them lowercase names; wire casing (Cf-Mitigated)
                    // would silently break their challenge detection
                    "headers" to ProxyObject.fromMap(
                        response.headers.entries()
                            .associate { it.key.lowercase() to it.value.joinToString(", ") }
                    ),
                    "cookies" to ProxyArray.fromList(cookies.map { cookieProxy(it, response.call.request.url.host) }),
                )
            )
            return ProxyObject.fromMap(mapOf("request" to request, "response" to responseObject, "data" to bytes))
        }

        private fun cookieProxy(cookie: io.ktor.http.Cookie, fallbackDomain: String): ProxyObject =
            ProxyObject.fromMap(
                mapOf(
                    "name" to cookie.name,
                    "value" to cookie.value,
                    "domain" to (cookie.domain ?: fallbackDomain),
                    "path" to (cookie.path ?: "/"),
                    "expires" to cookie.expires?.let { dateCtor.newInstance(it.timestamp.toDouble()) },
                )
            )
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

/** Decodes the five XML entities plus numeric `&#NNN;` / `&#xHH;` forms. No dependency. */
private val numericHexEntity = Regex("&#[xX]([0-9a-fA-F]+);")
private val numericDecEntity = Regex("&#([0-9]+);")

internal fun decodeHtmlEntities(input: String): String {
    var result = input
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
    result = numericHexEntity.replace(result) { m -> codePointOrLiteral(m.groupValues[1], 16, m.value) }
    result = numericDecEntity.replace(result) { m -> codePointOrLiteral(m.groupValues[1], 10, m.value) }
    return result.replace("&amp;", "&")
}

// supports astral code points (emoji in titles); malformed/out-of-range entities are
// left as literal text rather than throwing at the trust boundary
private fun codePointOrLiteral(digits: String, radix: Int, literal: String): String {
    val codePoint = digits.toIntOrNull(radix) ?: return literal
    if (!Character.isValidCodePoint(codePoint)) return literal
    return String(Character.toChars(codePoint))
}

// string<->string base64: extension bundles pass/receive plain strings, not byte buffers, so
// the string is treated as UTF-8 (matching the native Swift app's Data(str.utf8) convention)
internal fun base64Encode(input: String): String =
    Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))

internal fun base64Decode(input: String): String =
    Base64.getDecoder().decode(input).toString(Charsets.UTF_8)

internal fun md5Hash(input: String): String =
    MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
