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

@Tag("live")
class LiveChallengeSmokeTest {
    @Test
    fun productionSolverHarvestsAndPersistsCfClearance() {
        val cefDir = Paths.get(System.getProperty("user.home"), ".mango-jcef")
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        MangoDatabase.Schema.create(driver)
        val db = MangoDatabase(driver)

        val jcef = JcefManager(cefDir)
        val catalog = FakeCatalogRepository()
        val solver = JcefChallengeSolver(jcef, catalog, cookieStoreFor = { SqlCookieStore(db, it) })

        try {
            val ok = runBlocking { solver.solve("MangaBat", "https://www.mangabats.com") }
            assertTrue(ok, "expected the solver to harvest clearance")

            val cookies = runBlocking { SqlCookieStore(db, "MangaBat").cookiesFor("mangabats.com") }
            println("SMOKE harvested cookies: ${cookies.map { it.name }}")
            assertTrue(cookies.any { it.name == "cf_clearance" }, "expected cf_clearance in the jar")
            assertTrue(catalog.userAgentsBySourceId["MangaBat"] != null, "expected the UA to be pinned")

            runBlocking {
                io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO).use { http ->
                    val response = http.get("https://www.mangabats.com/") {
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
                    assertTrue(
                        response.status.value == 200,
                        "replay with harvested clearance expected 200, got ${response.status.value}"
                    )
                    assertTrue(
                        response.headers["cf-mitigated"] == null,
                        "replay was re-challenged (cf-mitigated=${response.headers["cf-mitigated"]})"
                    )
                }
            }
        } finally {
            jcef.dispose()
        }
    }
}
