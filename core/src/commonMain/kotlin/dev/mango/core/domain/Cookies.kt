package dev.mango.core.domain

/** One persisted HTTP cookie. expiresAtMillis null = session cookie, kept until replaced. */
data class StoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val expiresAtMillis: Long? = null,
)

/**
 * Cookie persistence for one source; the per-source scoping happens at construction, so a
 * consumer only ever sees its own source's cookies. This is the landing slot for
 * cf_clearance harvested by the challenge-solve flow.
 */
interface CookieStore {
    /** Non-expired cookies whose domain matches [host] (exact or parent domain). */
    suspend fun cookiesFor(host: String): List<StoredCookie>
    suspend fun put(cookie: StoredCookie)
}
