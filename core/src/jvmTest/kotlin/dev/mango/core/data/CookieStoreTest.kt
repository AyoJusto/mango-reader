package dev.mango.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.mango.core.db.MangoDatabase
import dev.mango.core.domain.StoredCookie
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/** [SqlCookieStore] through the CookieStore contract: matching, expiry, source isolation. */
class CookieStoreTest {
    private fun newDb(): MangoDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        MangoDatabase.Schema.create(driver)
        return MangoDatabase(driver)
    }

    private fun fixedClock(nowMillis: Long): Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillis)
    }

    @Test
    fun putAndReadBackOnExactDomain() = runTest {
        val store = SqlCookieStore(newDb(), "MangaBat")
        store.put(StoredCookie(name = "session", value = "abc", domain = "example.com"))

        assertEquals(listOf("abc"), store.cookiesFor("example.com").map { it.value })
    }

    @Test
    fun parentDomainCookieMatchesSubdomainHost() = runTest {
        val store = SqlCookieStore(newDb(), "MangaBat")
        store.put(StoredCookie(name = "session", value = "abc", domain = ".example.com"))

        assertEquals(1, store.cookiesFor("img.example.com").size)
    }

    @Test
    fun lookalikeDomainDoesNotMatch() = runTest {
        val store = SqlCookieStore(newDb(), "MangaBat")
        store.put(StoredCookie(name = "session", value = "abc", domain = "example.com"))

        assertTrue(store.cookiesFor("evilexample.com").isEmpty())
    }

    @Test
    fun expiredCookieIsDroppedSessionCookieIsKept() = runTest {
        val store = SqlCookieStore(newDb(), "MangaBat", clock = fixedClock(1_000_000))
        store.put(StoredCookie(name = "old", value = "x", domain = "example.com", expiresAtMillis = 999_999))
        store.put(StoredCookie(name = "live", value = "y", domain = "example.com", expiresAtMillis = 1_000_001))
        store.put(StoredCookie(name = "session", value = "z", domain = "example.com"))

        assertEquals(setOf("live", "session"), store.cookiesFor("example.com").map { it.name }.toSet())
    }

    @Test
    fun putWithSameKeyReplacesTheValue() = runTest {
        val store = SqlCookieStore(newDb(), "MangaBat")
        store.put(StoredCookie(name = "session", value = "old", domain = "example.com"))
        store.put(StoredCookie(name = "session", value = "new", domain = "example.com"))

        assertEquals(listOf("new"), store.cookiesFor("example.com").map { it.value })
    }

    @Test
    fun sourcesAreIsolated() = runTest {
        val db = newDb()
        SqlCookieStore(db, "MangaBat").put(StoredCookie(name = "session", value = "abc", domain = "example.com"))

        assertTrue(SqlCookieStore(db, "Toonily").cookiesFor("example.com").isEmpty())
    }
}
