package dev.mango.app.webview

import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.ChallengeSolver
import dev.mango.core.domain.CookieStore

/** On a passed challenge, stores the clearance cookies and pins the UA to the source. */
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
        // Pinning the UA also evicts the cached engine so the next request uses the new UA and cookies.
        catalog.setUserAgent(sourceId, WebViewUserAgent)
        return true
    }
}
