package dev.mango.app.webview

import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.ChallengeSolver
import dev.mango.core.domain.CookieStore

/**
 * [ChallengeSolver] backed by the embedded browser ([JcefManager]). On a passed challenge it
 * writes the harvested clearance cookies into the source's cookie jar and pins the browser's
 * user-agent to the source (via [CatalogRepository.setUserAgent], which also evicts the cached
 * engine instance so the next request rebuilds with the pinned UA + cookies).
 */
class JcefChallengeSolver(
    private val jcef: JcefManager,
    private val catalog: CatalogRepository,
    private val cookieStoreFor: (sourceId: String) -> CookieStore,
    private val onProgress: (JcefManager.Progress) -> Unit = {},
) : ChallengeSolver {
    override suspend fun solve(sourceId: String, url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return false
        val cookies = jcef.solve(host, url, onProgress) ?: return false
        if (cookies.isEmpty()) return false

        val store = cookieStoreFor(sourceId)
        cookies.forEach { store.put(it) }
        // pin the exact UA the clearance is bound to; this also evicts the source's cached
        // engine so its next request goes out with the new UA + freshly stored cookies
        catalog.setUserAgent(sourceId, WebViewUserAgent)
        return true
    }
}
