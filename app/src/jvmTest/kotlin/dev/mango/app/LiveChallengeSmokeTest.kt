package dev.mango.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.mango.app.webview.JcefChallengeSolver
import dev.mango.app.webview.JcefManager
import dev.mango.core.data.SqlCookieStore
import dev.mango.core.db.MangoDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import java.nio.file.Paths
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag

/**
 * Live verification. Drives the PRODUCTION solve path — real JcefManager +
 * JcefChallengeSolver + real SqlCookieStore — against a real Cloudflare site, and asserts
 * cf_clearance was harvested and persisted into the source's cookie jar where the host reads it.
 *
 * Tagged "live": excluded from normal runs (opens a real browser window). Run on demand:
 *   .\gradlew.bat :app:jvmTest -Plive --tests *LiveChallengeSmokeTest*
 * CEF installs into a persistent cache dir, so only the first ever run downloads it. toonily's
 * managed challenge auto-passes in a real browser, so it runs unattended; if a source ever
 * shows an interactive checkbox, click it and the harvest completes.
 */
@Tag("live")
class LiveChallengeSmokeTest {
    @Test
    fun productionSolverHarvestsAndPersistsCfClearance() {
        // persistent CEF cache under the user home — download happens at most once, ever
        val cefDir = Paths.get(System.getProperty("user.home"), ".mango-jcef")
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        MangoDatabase.Schema.create(driver)
        val db = MangoDatabase(driver)

        val jcef = JcefManager(cefDir)
        val catalog = FakeCatalogRepository()
        val solver = JcefChallengeSolver(jcef, catalog, cookieStoreFor = { SqlCookieStore(db, it) })

        try {
            val ok = runBlocking { solver.solve("Toonily", "https://toonily.com") }
            assertTrue(ok, "expected the solver to harvest clearance")

            val cookies = runBlocking { SqlCookieStore(db, "Toonily").cookiesFor("toonily.com") }
            println("SMOKE harvested cookies: ${cookies.map { it.name }}")
            assertTrue(cookies.any { it.name == "cf_clearance" }, "expected cf_clearance in the jar")
            assertTrue(catalog.userAgentsBySourceId["Toonily"] != null, "expected the UA to be pinned")

            // the end-to-end proof harvesting alone doesn't give: the clearance must also
            // satisfy Cloudflare when replayed by the JVM HTTP stack (same UA + IP; the
            // TLS/CF-environment axis). The engine's header-casing axis is covered by the
            // unit regression test bundleHeaderNamesAreCanonicalizedOnTheWire, not here.
            runBlocking {
                io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO).use { http ->
                    val response = http.get("https://toonily.com/") {
                        header(
                            io.ktor.http.HttpHeaders.Cookie,
                            cookies.joinToString("; ") { "${it.name}=${it.value}" },
                        )
                        header(io.ktor.http.HttpHeaders.UserAgent, dev.mango.app.webview.WebViewUserAgent)
                    }
                    println(
                        "SMOKE replay: status=${response.status.value} " +
                            "cf-mitigated=${response.headers["cf-mitigated"]}",
                    )
                    assertTrue(response.status.value == 200, "replay with harvested clearance expected 200, got ${response.status.value}")
                    assertTrue(response.headers["cf-mitigated"] == null, "replay was re-challenged (cf-mitigated=${response.headers["cf-mitigated"]})")
                }
            }
        } finally {
            jcef.dispose()
        }
    }
}
