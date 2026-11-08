package dev.mango.core.engine

import dev.mango.core.domain.CookieStore
import dev.mango.core.domain.StoredCookie
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.parseServerSetCookieHeader
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.milliseconds

/** Guest-facing `Application` surface; all policy (rate limits, allowlists, timeouts) is enforced here, never in JS. */
internal const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

class ApplicationHost(
    private val http: HttpClient? = null,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val onResponse: ((url: String, status: Int, body: ByteArray) -> Unit)? = null,
    private val minRequestIntervalMillis: Long = 500,
    private val requestTimeoutMillis: Long = 30_000,
    private val cookieStore: CookieStore? = null,
    private val sleeper: (Double) -> Unit = { seconds -> Thread.sleep((seconds * 1000).toLong()) },
) {
    // JSON strings: polyglot Values die with their context.
    private val state = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())
    private val secureState = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())

    // One lock for every host, so rate-limit waits serialize across sources.
    private val hostDispatchLock = Any()
    private val lastDispatchAtMillis = mutableMapOf<String, Long>()

    private val log = java.util.logging.Logger.getLogger(ApplicationHost::class.java.name)

    fun applicationProxyFor(context: Context, callJob: Job? = null): ApplicationProxy =
        ApplicationProxy(context, callJob)

    inner class ApplicationProxy internal constructor(
        private val context: Context,
        private val callJob: Job? = null,
    ) : ProxyObject {
        private val selectors = mutableMapOf<String, Pair<Value, String>>()
        private var nextSelectorId = 1
        private val discoverSections = mutableMapOf<String, Value>()
        private var redirectHandlerSelectorId: String? = null

        /** (id, requestSelectorId, responseSelectorId), run in order by [scheduleRequest]. */
        val interceptors = mutableListOf<Triple<String, String, String>>()

        private val dateCtor: Value get() = context.eval("js", "Date")
        private val promiseCtor: Value get() = context.eval("js", "Promise")

        // Dates round-trip via a sentinel object; bundles call .getTime() on revived state at module load.
        private val stateStringify: Value
            get() = context.eval(
                "js",
                """(v) => JSON.stringify(v, function (k, val) { const o = this[k]; return o instanceof Date ? { "${'$'}mangoDate${'$'}": o.getTime() } : val; })""",
            )
        private val stateParse: Value
            get() = context.eval(
                "js",
                """(s) => JSON.parse(s, (k, val) => (val !== null && typeof val === "object" && typeof val["${'$'}mangoDate${'$'}"] === "number") ? new Date(val["${'$'}mangoDate${'$'}"]) : val)""",
            )

        private val members: Map<String, Any?> = mapOf(
            "isResourceLimited" to false,
            "filterAdultTitles" to false,
            "filterMatureTitles" to false,
            "getDefaultUserAgent" to ProxyExecutable { userAgent },
            "getState" to ProxyExecutable { args ->
                state[args[0].asString()]?.let { stateParse.execute(it) }
            },
            "setState" to ProxyExecutable { args ->
                val serialized = stateStringify.execute(args[0])
                if (serialized.isNull) state.remove(args[1].asString())
                else state[args[1].asString()] = serialized.asString()
                null
            },
            "getSecureState" to ProxyExecutable { args ->
                secureState[args[0].asString()]?.let { stateParse.execute(it) }
            },
            "setSecureState" to ProxyExecutable { args ->
                val serialized = stateStringify.execute(args[0])
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
                // @paperback/types gives the interval in seconds.
                sleeper(args[0].asDouble())
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

        // Claim all members except engine probes so a shim gap throws a named error, not undefined.
        override fun hasMember(key: String): Boolean = key != "then" && key != "toJSON"

        override fun getMember(key: String): Any? =
            if (members.containsKey(key)) members[key]
            else throw UnsupportedOperationException("Application.$key is not implemented")

        // Native promise chain: awaiting from Kotlin mid-call never settles (microtasks drain on outermost return).
        private fun scheduleRequest(requestArg: Value): Value {
            val client = http
                ?: throw UnsupportedOperationException("this ApplicationHost has no HTTP client")

            var bundle = requestInterceptorChain(requestArg)
                .invokeMember("then", ProxyExecutable { args -> dispatch(client, args[0]) })

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

        private fun requestInterceptorChain(request: Value): Value {
            var chain = promiseCtor.invokeMember("resolve", request)
            for ((_, requestSelectorId, _) in interceptors) {
                val (target, method) = selectors[requestSelectorId]
                    ?: throw IllegalArgumentException("unknown selector $requestSelectorId")
                chain = chain.invokeMember("then", ProxyExecutable { args -> target.invokeMember(method, args[0]) })
            }
            return chain
        }

        /** Runs the request interceptors on a host-built [request] without dispatching HTTP. */
        internal fun interceptRequest(request: ProxyObject): Value = requestInterceptorChain(context.asValue(request))

        private fun dispatch(client: HttpClient, request: Value): ProxyObject {
            val requestedUrl = request.getMember("url")?.takeIf { it.isString }?.asString()
                ?: throw IllegalArgumentException("request has no url")
            val requestedHost = runCatching { Url(requestedUrl).host }.getOrDefault(requestedUrl)

            // runBlocking adopts callJob so cancelling the extension call interrupts the in-flight request.
            val (response, bytes) = runBlocking(callJob ?: EmptyCoroutineContext) {
                awaitHostRateLimit(requestedHost)
                try {
                    withTimeout(requestTimeoutMillis.milliseconds) {
                        val r = execute(client, request, requestedUrl)
                        r to r.body<ByteArray>()
                    }
                } catch (e: TimeoutCancellationException) {
                    throw ExtensionNetworkException(
                        "request to $requestedUrl (host=$requestedHost) timed out after ${requestTimeoutMillis}ms",
                        e,
                    )
                } catch (e: IOException) {
                    throw ExtensionNetworkException(
                        "request to $requestedUrl (host=$requestedHost) failed: ${e.message}",
                        e,
                    )
                }
            }
            onResponse?.invoke(requestedUrl, response.status.value, bytes)

            val cookies = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                .map { parseServerSetCookieHeader(it) }
            cookieStore?.let { store ->
                runBlocking(callJob ?: EmptyCoroutineContext) {
                    // a jar write must never sink a response the site already served
                    runCatching {
                        cookies.forEach { c ->
                            store.put(
                                StoredCookie(
                                    name = c.name,
                                    value = c.value,
                                    domain = c.domain ?: response.call.request.url.host,
                                    path = c.path ?: "/",
                                    expiresAtMillis = c.expires?.timestamp,
                                )
                            )
                        }
                    }.onFailure {
                        log.warning("cookie jar write failed for $requestedHost: $it")
                    }
                }
            }
            val responseObject = ProxyObject.fromMap(
                mapOf(
                    "url" to response.call.request.url.toString(),
                    "status" to response.status.value,
                    // Multi-value headers are comma-joined; Set-Cookie arrives through `cookies`.
                    // keys lowercased: bundles do exact lookups like headers["cf-mitigated"]
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

    // Slot is reserved under the lock before delaying so concurrent same-host calls queue.
    private suspend fun awaitHostRateLimit(host: String) {
        val waitMillis = synchronized(hostDispatchLock) {
            val now = System.currentTimeMillis()
            val last = lastDispatchAtMillis[host]
            val wait = if (last == null) 0L else (minRequestIntervalMillis - (now - last)).coerceAtLeast(0L)
            lastDispatchAtMillis[host] = now + wait
            wait
        }
        if (waitMillis > 0) delay(waitMillis.milliseconds)
    }

    private suspend fun execute(
        client: HttpClient,
        request: Value,
        url: String,
    ): HttpResponse = client.request(url) {
        val methodName = request.getMember("method")?.takeIf { it.isString }?.asString() ?: "GET"
        method = HttpMethod.parse(methodName.uppercase())
        request.getMember("headers")?.takeIf { it.hasMembers() }?.let { h ->
            h.memberKeys.forEach { key -> header(canonicalHeaderName(key), h.getMember(key).asString()) }
        }
        if (headers[HttpHeaders.UserAgent] == null) header(HttpHeaders.UserAgent, userAgent)
        val cookiePairs = linkedMapOf<String, String>()
        cookieStore?.let { store ->
            runCatching { Url(url).host }.getOrNull()?.let { host ->
                // a jar read fault degrades to "no stored cookies", never an aborted call
                runCatching { store.cookiesFor(host) }
                    .onFailure { log.warning("cookie jar read failed for $host: $it") }
                    .getOrDefault(emptyList())
                    .forEach { cookiePairs[it.name] = it.value }
            }
        }
        request.getMember("cookies")?.takeIf { it.hasArrayElements() && it.arraySize > 0 }?.let { cookies ->
            (0 until cookies.arraySize).forEach { i ->
                val c = cookies.getArrayElement(i)
                cookiePairs[c.getMember("name").asString()] = c.getMember("value").asString()
            }
        }
        if (cookiePairs.isNotEmpty()) {
            header(HttpHeaders.Cookie, cookiePairs.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        request.getMember("data")?.takeIf { !it.isNull }?.let { data ->
            when {
                data.isString -> setBody(data.asString())
                data.isHostObject -> setBody(data.asHostObject<ByteArray>())
                data.hasBufferElements() -> setBody(bytesOf(data))
                else -> throw IllegalArgumentException("unsupported request data type: $data")
            }
        }
    }
}

/** Title-cases all-lowercase header names for the wire; lowercase over HTTP/1.1 trips Cloudflare. */
internal fun canonicalHeaderName(name: String): String = when {
    name.any { it.isUpperCase() } -> name
    name.startsWith("sec-") -> name
    else -> name.split('-').joinToString("-") { part -> part.replaceFirstChar { it.uppercaseChar() } }
}

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

// Malformed or out-of-range entities stay literal rather than throwing at the trust boundary.
private fun codePointOrLiteral(digits: String, radix: Int, literal: String): String {
    val codePoint = digits.toIntOrNull(radix) ?: return literal
    if (!Character.isValidCodePoint(codePoint)) return literal
    return String(Character.toChars(codePoint))
}

// Strings are treated as UTF-8, matching the native app.
internal fun base64Encode(input: String): String =
    Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))

internal fun base64Decode(input: String): String =
    Base64.getDecoder().decode(input).toString(Charsets.UTF_8)

internal fun md5Hash(input: String): String =
    MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
