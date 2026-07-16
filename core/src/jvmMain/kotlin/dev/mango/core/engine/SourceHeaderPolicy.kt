package dev.mango.core.engine

import dev.mango.core.domain.CookieStore
import dev.mango.core.domain.Page
import dev.mango.core.domain.SourceHeaderPolicy
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException

/**
 * [SourceHeaderPolicy] backed by a per-source [CookieStore] lookup and User-Agent resolver.
 * These never go through [ApplicationHost]: it only wraps calls the extension itself schedules,
 * so image bytes fetched directly by the app would otherwise ship with none of a source's
 * cookies or UA.
 */
class DefaultSourceHeaderPolicy(
    private val cookieStoreFor: (sourceId: String) -> CookieStore,
    private val userAgentFor: suspend (sourceId: String) -> String?,
) : SourceHeaderPolicy {
    private val log = java.util.logging.Logger.getLogger(DefaultSourceHeaderPolicy::class.java.name)

    override suspend fun headersFor(sourceId: String, url: String, headers: Map<String, String>): Map<String, String> {
        val canonical = headers.mapKeys { (name, _) -> canonicalHeaderName(name) }
        return try {
            val result = LinkedHashMap(canonical)

            val hasUserAgent = result.keys.any { it.equals(HttpHeaders.UserAgent, ignoreCase = true) }
            if (!hasUserAgent) {
                result[HttpHeaders.UserAgent] = userAgentFor(sourceId) ?: DEFAULT_USER_AGENT
            }

            val cookiePairs = linkedMapOf<String, String>()
            val host = Url(url).host
            cookieStoreFor(sourceId).cookiesFor(host).forEach { cookiePairs[it.name] = it.value }

            val existingCookieKey = result.keys.firstOrNull { it.equals(HttpHeaders.Cookie, ignoreCase = true) }
            val existingCookieValue = existingCookieKey?.let { result.remove(it) }
            existingCookieValue?.let { value -> parseCookiePairs(value).forEach { (name, v) -> cookiePairs[name] = v } }

            // Cookie names/values are joined as-is: Ktor rejects illegal header bytes (CR/LF) at
            // request build, so a poisoned jar value fails the fetch instead of smuggling a request.
            if (cookiePairs.isNotEmpty()) {
                result[HttpHeaders.Cookie] = cookiePairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warning("header policy failed for source $sourceId ($url): $e")
            canonical
        }
    }

    // ponytail: one UA + cookie lookup per page; hoist per-source resolution if chapter load ever drags
    override suspend fun withPolicyHeaders(sourceId: String, pages: List<Page>): List<Page> =
        pages.map { page -> page.copy(headers = headersFor(sourceId, page.url, page.headers)) }

    private fun parseCookiePairs(cookieHeader: String): List<Pair<String, String>> =
        cookieHeader.split(';').mapNotNull { part ->
            val trimmed = part.trim()
            val separator = trimmed.indexOf('=')
            if (separator < 0) null else trimmed.substring(0, separator) to trimmed.substring(separator + 1)
        }
}
