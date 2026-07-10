package dev.mango.core.data

import dev.mango.core.db.MangoDatabase
import dev.mango.core.domain.CookieStore
import dev.mango.core.domain.StoredCookie
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** SQLDelight-backed [CookieStore], scoped to [sourceId] at construction. */
class SqlCookieStore(
    private val db: MangoDatabase,
    private val sourceId: String,
    private val context: CoroutineContext = Dispatchers.Default,
    private val clock: Clock = Clock.System,
) : CookieStore {
    override suspend fun cookiesFor(host: String): List<StoredCookie> = withContext(context) {
        val now = clock.now().toEpochMilliseconds()
        db.cookiesQueries.selectCookies(sourceId).executeAsList()
            .filter { it.expires_at == null || it.expires_at > now }
            .filter { domainMatches(host, it.domain) }
            .map { StoredCookie(it.name, it.value_, it.domain, it.path, it.expires_at) }
    }

    override suspend fun put(cookie: StoredCookie) = withContext(context) {
        db.cookiesQueries.upsertCookie(
            source_id = sourceId,
            name = cookie.name,
            value_ = cookie.value,
            domain = cookie.domain,
            path = cookie.path,
            expires_at = cookie.expiresAtMillis,
        )
        Unit
    }

    // ponytail: RFC 6265 domain suffix match only, path ignored; per-path matching can
    // arrive with a real source that needs it
    private fun domainMatches(host: String, domain: String): Boolean {
        val d = domain.removePrefix(".")
        return host == d || host.endsWith(".$d")
    }
}
