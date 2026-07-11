package dev.mango.app

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.mango.core.data.FileDownloadManager
import dev.mango.core.data.InkdexRepo
import dev.mango.core.data.PaperbackCatalogRepository
import dev.mango.core.data.SqlCookieStore
import dev.mango.core.data.SqlLibraryRepository
import dev.mango.core.db.MangoDatabase
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.DownloadManager
import dev.mango.core.domain.ExtensionRepo
import dev.mango.core.domain.LibraryRepository
import dev.mango.core.engine.ApplicationHost
import dev.mango.core.engine.PaperbackExtension
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/**
 * The composition root: manual constructor DI, no framework (PLANNING §13). Wires the
 * production :core implementations together over a single on-disk data directory.
 */
class AppGraph(dataDir: Path = defaultDataDir()) {
    // db and http stay private: screens talk to the repository ports only (CLAUDE.md
    // boundary); publishing either would hand the UI raw network and DB access
    private val db: MangoDatabase
    private val http: HttpClient = HttpClient(CIO)
    val library: LibraryRepository
    val catalog: CatalogRepository
    val downloads: DownloadManager
    val extensions: ExtensionRepo

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

        library = SqlLibraryRepository(db)
        catalog = PaperbackCatalogRepository(
            db = db,
            bundleDir = bundleDir,
            sourceFactory = { sourceId, bundleJs ->
                PaperbackExtension(
                    sourceId,
                    bundleJs,
                    ApplicationHost(http = http, cookieStore = SqlCookieStore(db, sourceId)),
                )
            },
        )
        downloads = FileDownloadManager(db = db, catalog = catalog, http = http, root = downloadsDir)
        extensions = InkdexRepo(http = http, bundleDir = bundleDir, catalog = catalog)
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
