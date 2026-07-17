package dev.mango.core.engine

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.mango.core.data.SqlCookieStore
import dev.mango.core.db.MangoDatabase
import dev.mango.core.domain.CookieStore
import dev.mango.core.domain.Page
import dev.mango.core.domain.SourceImageRequest
import dev.mango.core.domain.StoredCookie
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/** A [CookieStore] whose [cookiesFor] always throws — proves a jar fault degrades, never sinks the caller. */
private class ThrowingCookieStore : CookieStore {
    override suspend fun cookiesFor(host: String): List<StoredCookie> = error("jar read boom")
    override suspend fun put(cookie: StoredCookie) = error("not used")
}

/**
 * [DefaultSourceHeaderPolicy] mirrors [ApplicationHost]'s wire-boundary precedence for image
 * fetches, which never go through ApplicationHost itself.
 */
class SourceHeaderPolicyTest {
    private fun newStore(): CookieStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        MangoDatabase.Schema.create(driver)
        return SqlCookieStore(MangoDatabase(driver), "src")
    }

    private fun policy(
        store: CookieStore = newStore(),
        userAgent: suspend (String) -> String? = { null },
        interceptRequests: (suspend (String, List<SourceImageRequest>) -> List<SourceImageRequest>)? = null,
    ) = DefaultSourceHeaderPolicy(cookieStoreFor = { store }, userAgentFor = userAgent, interceptRequests = interceptRequests)

    @Test
    fun pinnedUserAgentFillsInWhenHeadersLackOne() = runBlocking {
        val result = policy(userAgent = { "Pinned/1.0" })
            .headersFor("src", "https://example.com/a.jpg", emptyMap())

        assertEquals("Pinned/1.0", result["User-Agent"])
    }

    @Test
    fun callerSuppliedUserAgentWinsOverPinnedRegardlessOfCasing() = runBlocking {
        val result = policy(userAgent = { "Pinned/1.0" }).headersFor(
            "src",
            "https://example.com/a.jpg",
            mapOf("user-agent" to "Caller/2.0"),
        )

        assertEquals("Caller/2.0", result["User-Agent"])
    }

    @Test
    fun defaultUserAgentUsedWhenNothingPinned() = runBlocking {
        val result = policy(userAgent = { null }).headersFor("src", "https://example.com/a.jpg", emptyMap())

        assertEquals(DEFAULT_USER_AGENT, result["User-Agent"])
    }

    @Test
    fun jarCookiesForTheUrlsHostLandInACookieHeader() = runBlocking {
        val store = newStore()
        store.put(StoredCookie(name = "cf_clearance", value = "token", domain = "example.com"))

        val result = policy(store = store).headersFor("src", "https://example.com/a.jpg", emptyMap())

        assertEquals("cf_clearance=token", result["Cookie"])
    }

    @Test
    fun callerCookiePairOverridesJarPairOnNameCollisionAndNonCollidingPairsSurvive() = runBlocking {
        val store = newStore()
        store.put(StoredCookie(name = "session", value = "stale", domain = "example.com"))
        store.put(StoredCookie(name = "lang", value = "en", domain = "example.com"))

        val result = policy(store = store).headersFor(
            "src",
            "https://example.com/a.jpg",
            mapOf("Cookie" to "session=fresh"),
        )

        // Set, not string equality: the jar's own cookie order isn't part of its contract.
        val pairs = result.getValue("Cookie").split("; ").map { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(setOf("session" to "fresh", "lang" to "en"), pairs.toSet())
    }

    @Test
    fun headerNamesCanonicalize() = runBlocking {
        val result = policy().headersFor(
            "src",
            "https://example.com/a.jpg",
            mapOf("referer" to "https://example.com", "sec-ch-ua" to "x", "X-API-Key" to "k"),
        )

        assertEquals("https://example.com", result["Referer"])
        assertEquals("x", result["sec-ch-ua"])
        assertEquals("k", result["X-API-Key"])
    }

    @Test
    fun aThrowingCookieStoreDegradesToCanonicalizedInputHeadersWithoutThrowing() = runBlocking {
        val result = policy(store = ThrowingCookieStore(), userAgent = { "Pinned/1.0" }).headersFor(
            "src",
            "https://example.com/a.jpg",
            mapOf("referer" to "https://example.com"),
        )

        assertEquals(mapOf("Referer" to "https://example.com"), result)
    }

    @Test
    fun withPolicyHeadersAppliesTheInterceptedUrlAndMergesHeadersOntoThePage() = runBlocking {
        val result = policy(
            userAgent = { "Pinned/1.0" },
            interceptRequests = { _, requests ->
                requests.map { it.copy(url = it.url + "?signed", headers = it.headers + ("X-Marker" to "1")) }
            },
        ).withPolicyHeaders("src", listOf(Page(index = 0, url = "https://example.com/a.jpg")))

        val page = result.single()
        assertEquals("https://example.com/a.jpg?signed", page.url)
        assertEquals("1", page.headers["X-Marker"])
        assertEquals("Pinned/1.0", page.headers["User-Agent"])
    }

    @Test
    fun interceptorCookiePairOverridesJarPairOnNameCollision() = runBlocking {
        val store = newStore()
        store.put(StoredCookie(name = "session", value = "stale", domain = "example.com"))
        val result = policy(
            store = store,
            interceptRequests = { _, requests -> requests.map { it.copy(headers = it.headers + ("Cookie" to "session=fresh")) } },
        ).withPolicyHeaders("src", listOf(Page(index = 0, url = "https://example.com/a.jpg")))

        assertEquals("session=fresh", result.single().headers["Cookie"])
    }

    @Test
    fun pinnedUserAgentStillAppliesWhenTheInterceptorSetsNone() = runBlocking {
        val result = policy(
            userAgent = { "Pinned/1.0" },
            interceptRequests = { _, requests -> requests },
        ).withPolicyHeaders("src", listOf(Page(index = 0, url = "https://example.com/a.jpg")))

        assertEquals("Pinned/1.0", result.single().headers["User-Agent"])
    }

    @Test
    fun aMisSizedInterceptBatchDegradesEveryPageInsteadOfPairingByIndex() = runBlocking {
        val result = policy(
            userAgent = { "Pinned/1.0" },
            interceptRequests = { _, requests -> requests.take(1).map { it.copy(url = it.url + "?signed") } },
        ).withPolicyHeaders(
            "src",
            listOf(
                Page(index = 0, url = "https://example.com/a.jpg"),
                Page(index = 1, url = "https://example.com/b.jpg"),
            ),
        )

        assertEquals(listOf("https://example.com/a.jpg", "https://example.com/b.jpg"), result.map { it.url })
        assertEquals("Pinned/1.0", result.first().headers["User-Agent"])
    }

    @Test
    fun aThrowingInterceptBatchDegradesToJarAndUserAgentOnlyPages() = runBlocking {
        val store = newStore()
        store.put(StoredCookie(name = "cf_clearance", value = "token", domain = "example.com"))
        val result = policy(
            store = store,
            userAgent = { "Pinned/1.0" },
            interceptRequests = { _, _ -> error("batch boom") },
        ).withPolicyHeaders(
            "src",
            listOf(Page(index = 0, url = "https://example.com/a.jpg", headers = mapOf("Referer" to "https://example.com"))),
        )

        val page = result.single()
        assertEquals("https://example.com/a.jpg", page.url)
        assertEquals("Pinned/1.0", page.headers["User-Agent"])
        assertEquals("https://example.com", page.headers["Referer"])
        assertEquals("cf_clearance=token", page.headers["Cookie"])
    }
}
