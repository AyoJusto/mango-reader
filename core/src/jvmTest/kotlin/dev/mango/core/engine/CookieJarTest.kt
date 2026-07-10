package dev.mango.core.engine

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.mango.core.data.SqlCookieStore
import dev.mango.core.db.MangoDatabase
import dev.mango.core.domain.CookieStore
import dev.mango.core.domain.StoredCookie
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject

/**
 * The per-source cookie jar at the host boundary (M2.3): stored cookies ride outgoing
 * requests, Set-Cookie responses land in the store, and the extension's own cookies win.
 */
class CookieJarTest {
    private fun newStore(): CookieStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        MangoDatabase.Schema.create(driver)
        return SqlCookieStore(MangoDatabase(driver), "Test")
    }

    private fun scheduleRequest(host: ApplicationHost, request: ProxyObject) {
        Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build().use { context ->
            val app = host.applicationProxyFor(context)
            val schedule = app.getMember("scheduleRequest") as ProxyExecutable
            val raw = schedule.execute(context.asValue(request))
            awaitPromise(context, context.asValue(raw), "scheduleRequest")
        }
    }

    @Test
    fun storedCookieRidesTheOutgoingRequest() = runBlocking {
        val store = newStore()
        store.put(StoredCookie(name = "cf_clearance", value = "token", domain = "example.com"))
        var sentCookieHeader: String? = null
        val engine = MockEngine { request ->
            sentCookieHeader = request.headers[HttpHeaders.Cookie]
            respond("ok".encodeToByteArray(), HttpStatusCode.OK)
        }
        val host = ApplicationHost(http = HttpClient(engine), cookieStore = store)

        scheduleRequest(host, ProxyObject.fromMap(mapOf("url" to "https://example.com/api", "method" to "GET")))

        assertEquals("cf_clearance=token", sentCookieHeader)
    }

    @Test
    fun setCookieResponseLandsInTheStore() = runBlocking {
        val store = newStore()
        val engine = MockEngine {
            respond(
                "ok".encodeToByteArray(),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.SetCookie, "session=abc; Domain=example.com; Path=/"),
            )
        }
        val host = ApplicationHost(http = HttpClient(engine), cookieStore = store)

        scheduleRequest(host, ProxyObject.fromMap(mapOf("url" to "https://example.com/api", "method" to "GET")))

        assertEquals(listOf("abc"), store.cookiesFor("example.com").map { it.value })
    }

    @Test
    fun cookieSetInOneCallRidesTheNextCallFromAFreshContext() = runBlocking {
        val store = newStore()
        var sentCookieHeader: String? = null
        val engine = MockEngine { request ->
            sentCookieHeader = request.headers[HttpHeaders.Cookie]
            respond(
                "ok".encodeToByteArray(),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.SetCookie, "session=abc; Domain=example.com; Path=/"),
            )
        }
        val host = ApplicationHost(http = HttpClient(engine), cookieStore = store)
        val request = ProxyObject.fromMap(mapOf("url" to "https://example.com/api", "method" to "GET"))

        scheduleRequest(host, request) // fresh context: nothing to send, Set-Cookie lands
        assertEquals(null, sentCookieHeader)
        scheduleRequest(host, request) // second fresh context: the jar carries it over

        assertEquals("session=abc", sentCookieHeader)
    }

    @Test
    fun jarAndExtensionCookiesMergeWhenNamesDiffer() = runBlocking {
        val store = newStore()
        store.put(StoredCookie(name = "cf_clearance", value = "token", domain = "example.com"))
        var sentCookieHeader: String? = null
        val engine = MockEngine { request ->
            sentCookieHeader = request.headers[HttpHeaders.Cookie]
            respond("ok".encodeToByteArray(), HttpStatusCode.OK)
        }
        val host = ApplicationHost(http = HttpClient(engine), cookieStore = store)

        scheduleRequest(
            host,
            ProxyObject.fromMap(
                mapOf(
                    "url" to "https://example.com/api",
                    "method" to "GET",
                    "cookies" to ProxyArray.fromList(
                        listOf(ProxyObject.fromMap(mapOf("name" to "lang", "value" to "en")))
                    ),
                )
            ),
        )

        assertEquals("cf_clearance=token; lang=en", sentCookieHeader)
    }

    @Test
    fun extensionProvidedCookieWinsOverTheJar() = runBlocking {
        val store = newStore()
        store.put(StoredCookie(name = "session", value = "stale", domain = "example.com"))
        var sentCookieHeader: String? = null
        val engine = MockEngine { request ->
            sentCookieHeader = request.headers[HttpHeaders.Cookie]
            respond("ok".encodeToByteArray(), HttpStatusCode.OK)
        }
        val host = ApplicationHost(http = HttpClient(engine), cookieStore = store)

        scheduleRequest(
            host,
            ProxyObject.fromMap(
                mapOf(
                    "url" to "https://example.com/api",
                    "method" to "GET",
                    "cookies" to ProxyArray.fromList(
                        listOf(ProxyObject.fromMap(mapOf("name" to "session", "value" to "fresh")))
                    ),
                )
            ),
        )

        assertEquals("session=fresh", sentCookieHeader)
    }
}
