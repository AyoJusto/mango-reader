package dev.mango.core.domain

/** One persisted HTTP cookie. expiresAtMillis null = session cookie, kept until replaced. */
data class StoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val expiresAtMillis: Long? = null,
)

/** Cookie persistence scoped to one source; the landing slot for harvested cf_clearance. */
interface CookieStore {
    /** Non-expired cookies whose domain matches [host] (exact or parent domain). */
    suspend fun cookiesFor(host: String): List<StoredCookie>
    suspend fun put(cookie: StoredCookie)
}
