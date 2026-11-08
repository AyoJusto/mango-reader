package dev.mango.core.engine

import dev.mango.core.domain.CookieStore
import dev.mango.core.domain.Page
import dev.mango.core.domain.SourceHeaderPolicy
import dev.mango.core.domain.SourceImageRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

/** Applies the cookie jar and UA to image fetches that bypass [ApplicationHost]. */
class DefaultSourceHeaderPolicy(
    private val cookieStoreFor: (sourceId: String) -> CookieStore,
    private val userAgentFor: suspend (sourceId: String) -> String?,
    private val interceptRequests: (suspend (sourceId: String, List<SourceImageRequest>) -> List<SourceImageRequest>)? = null,
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

            // Ktor rejects CR/LF in header values, so a poisoned jar value fails the fetch instead of smuggling.
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

    private val coverHeaderCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    private val coverHeaderLock = kotlinx.coroutines.sync.Mutex()

    // The first cover's interceptor headers serve every cover; per-URL signed covers would break.
    override suspend fun coverHeadersFor(sourceId: String, url: String): Map<String, String> {
        val added = coverHeaderCache[sourceId] ?: coverHeaderLock.withLock {
            coverHeaderCache[sourceId] ?: interceptedCoverHeaders(sourceId, url).also {
                coverHeaderCache[sourceId] = it
            }
        }
        return headersFor(sourceId, url, added)
    }

    private suspend fun interceptedCoverHeaders(sourceId: String, url: String): Map<String, String> {
        val intercept = interceptRequests ?: return emptyMap()
        return try {
            val result = intercept(sourceId, listOf(SourceImageRequest(url, emptyMap())))
            if (result.size != 1) {
                log.warning("cover interception returned ${result.size} items for 1 request (source $sourceId)")
            }
            result.singleOrNull()?.headers ?: emptyMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warning("cover interception failed for source $sourceId: $e")
            emptyMap()
        }
    }

    override suspend fun withPolicyHeaders(sourceId: String, pages: List<Page>): List<Page> {
        val intercepted = interceptRequests?.let { intercept ->
            try {
                val result = intercept(sourceId, pages.map { SourceImageRequest(it.url, it.headers) })
                // Pairing is by index; wrong headers on the wrong page is worse than no interception.
                if (result.size == pages.size) result else {
                    log.warning(
                        "image request interception returned ${result.size} items for ${pages.size} pages " +
                                "(source $sourceId) - degrading batch"
                    )
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warning("image request interception failed for source $sourceId (batch of ${pages.size}): $e")
                null
            }
        }
        return pages.mapIndexed { index, page ->
            val prepared = intercepted?.getOrNull(index) ?: SourceImageRequest(page.url, page.headers)
            page.copy(url = prepared.url, headers = headersFor(sourceId, prepared.url, prepared.headers))
        }
    }

    private fun parseCookiePairs(cookieHeader: String): List<Pair<String, String>> =
        cookieHeader.split(';').mapNotNull { part ->
            val trimmed = part.trim()
            val separator = trimmed.indexOf('=')
            if (separator < 0) null else trimmed.substring(0, separator) to trimmed.substring(separator + 1)
        }
}
