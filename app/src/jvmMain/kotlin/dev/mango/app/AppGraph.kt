package dev.mango.app

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.mango.core.data.FileDownloadManager
import dev.mango.core.data.InkdexRepo
import dev.mango.core.data.PaperbackCatalogRepository
import dev.mango.core.data.SqlCatalogCache
import dev.mango.core.data.SqlCookieStore
import dev.mango.core.data.SqlLibraryRepository
import dev.mango.core.db.MangoDatabase
import dev.mango.app.webview.JcefChallengeSolver
import dev.mango.app.webview.JcefManager
import dev.mango.app.webview.SingleFlightChallengeSolver
import dev.mango.core.domain.CatalogCache
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.ChallengeSolver
import dev.mango.core.domain.DownloadManager
import dev.mango.core.domain.ExtensionRepo
import dev.mango.core.domain.LibraryRepository
import dev.mango.core.domain.SourceHeaderPolicy
import dev.mango.core.engine.ApplicationHost
import dev.mango.core.engine.DefaultSourceHeaderPolicy
import dev.mango.core.engine.PaperbackExtension
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val HTTP_CONNECT_TIMEOUT_MILLIS = 10_000L

// sized for full image downloads, not just extension API calls; scheduleRequest applies
// its own tighter timeout inside ApplicationHost for individual bundle-issued requests
private const val HTTP_REQUEST_TIMEOUT_MILLIS = 120_000L
private const val HTTP_SOCKET_TIMEOUT_MILLIS = 30_000L

/**
 * The composition root: manual constructor DI, no framework. Wires the production :core
 * implementations together over a single on-disk data directory.
 */
class AppGraph(dataDir: Path = defaultDataDir()) {
    // db and http stay private: screens talk to the repository ports only (CLAUDE.md
    // boundary); publishing either would hand the UI raw network and DB access
    private val db: MangoDatabase
    private val http: HttpClient = HttpClient(CIO) {
        engine { requestTimeout = 0 }
        install(HttpTimeout) {
            connectTimeoutMillis = HTTP_CONNECT_TIMEOUT_MILLIS
            requestTimeoutMillis = HTTP_REQUEST_TIMEOUT_MILLIS
            socketTimeoutMillis = HTTP_SOCKET_TIMEOUT_MILLIS
        }
    }
    val library: LibraryRepository
    val catalog: CatalogRepository
    val catalogCache: CatalogCache
    val downloads: DownloadManager
    val extensions: ExtensionRepo
    val challengeSolver: ChallengeSolver
    val headerPolicy: SourceHeaderPolicy
    val imageFetcherFactory: PolicyImageFetcher.Factory = PolicyImageFetcher.Factory(http)
    private val jcef: JcefManager

    init {
        Files.createDirectories(dataDir)
        val bundleDir = dataDir.resolve("bundles")
        val downloadsDir = dataDir.resolve("downloads")
        Files.createDirectories(bundleDir)
        Files.createDirectories(downloadsDir)

        val dbFile = dataDir.resolve("mango.db")
        val url = "jdbc:sqlite:$dbFile"
        // Fresh-DB detection by PRAGMA user_version, not file presence: the file appears as
        // soon as the driver opens, so a crash mid-create would otherwise leave a schemaless
        // file that bricks every later launch. user_version 0 means setup never completed,
        // which means no user data can exist yet — safe to start over from an empty file.
        // Any other version behind the current schema holds real user data (library,
        // downloads) and gets migrated in place instead.
        var driver = JdbcSqliteDriver(url, Properties())
        val v = userVersion(driver)
        when {
            v == 0L -> {
                driver.close()
                Files.deleteIfExists(dbFile)
                driver = JdbcSqliteDriver(url, Properties())
                MangoDatabase.Schema.create(driver)
                stamp(driver, MangoDatabase.Schema.version)
            }

            v < MangoDatabase.Schema.version -> {
                // migrate + stamp atomically: a crash between them would re-run the ALTERs
                // on the next launch ("duplicate column") and brick the DB permanently
                MangoDatabase(driver).transaction {
                    MangoDatabase.Schema.migrate(driver, v, MangoDatabase.Schema.version)
                    stamp(driver, MangoDatabase.Schema.version)
                }
            }

            v > MangoDatabase.Schema.version ->
                error("database is version $v but this app only knows ${MangoDatabase.Schema.version} — app downgrade?")
        }
        db = MangoDatabase(driver)
        db.cookiesQueries.deleteExpiredCookies(System.currentTimeMillis())

        library = SqlLibraryRepository(db)
        catalog = PaperbackCatalogRepository(
            db = db,
            bundleDir = bundleDir,
            sourceFactory = { sourceId, bundleJs, userAgent ->
                PaperbackExtension(
                    sourceId,
                    bundleJs,
                    if (userAgent != null) {
                        ApplicationHost(http = http, userAgent = userAgent, cookieStore = SqlCookieStore(db, sourceId))
                    } else {
                        ApplicationHost(http = http, cookieStore = SqlCookieStore(db, sourceId))
                    },
                )
            },
        )
        catalogCache = SqlCatalogCache(db)
        headerPolicy = DefaultSourceHeaderPolicy(
            cookieStoreFor = { sourceId -> SqlCookieStore(db, sourceId) },
            userAgentFor = { sourceId ->
                withContext(Dispatchers.IO) {
                    db.sourcesQueries.selectInstalledSource(sourceId).executeAsOneOrNull()?.user_agent
                }
            },
            interceptRequests = { sourceId, requests -> catalog.prepareImageRequests(sourceId, requests) },
        )
        downloads = FileDownloadManager(
            db = db,
            catalog = catalog,
            http = http,
            root = downloadsDir,
            headerPolicy = headerPolicy,
        )
        extensions = InkdexRepo(http = http, bundleDir = bundleDir, catalog = catalog)

        // embedded browser for the Cloudflare solve; CEF downloads into <dataDir>/jcef on
        // first use. A fresh SqlCookieStore per source is cheap (DB-backed, stateless).
        jcef = JcefManager(dataDir.resolve("jcef"))
        challengeSolver = SingleFlightChallengeSolver(
            JcefChallengeSolver(
                jcef = jcef,
                catalog = catalog,
                cookieStoreFor = { sourceId -> SqlCookieStore(db, sourceId) },
            )
        )
    }

    /** Release the embedded browser on app exit. */
    fun dispose() {
        jcef.dispose()
    }

    companion object {
        fun defaultDataDir(): Path =
            Paths.get(System.getenv("APPDATA") ?: System.getProperty("user.home"), "mango")

        private fun userVersion(driver: JdbcSqliteDriver): Long = driver.executeQuery(
            null,
            "PRAGMA user_version",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
            0,
        ).value ?: 0L

        private fun stamp(driver: JdbcSqliteDriver, version: Long) {
            driver.execute(null, "PRAGMA user_version = $version", 0)
        }
    }
}
