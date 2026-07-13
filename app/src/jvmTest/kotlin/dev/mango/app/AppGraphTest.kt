package dev.mango.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.mango.core.domain.MangaEntry
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class AppGraphTest {
    @Test
    fun constructingCreatesTheDbFileAndDataDirs() {
        val dataDir = Files.createTempDirectory("app-graph-test")

        AppGraph(dataDir)

        assertTrue(Files.exists(dataDir.resolve("mango.db")), "expected mango.db to be created")
        assertTrue(Files.isDirectory(dataDir.resolve("bundles")), "expected bundles/ to be created")
        assertTrue(Files.isDirectory(dataDir.resolve("downloads")), "expected downloads/ to be created")
    }

    @Test
    fun libraryRepositoryRoundTrips() = runBlocking {
        val dataDir = Files.createTempDirectory("app-graph-test")
        val graph = AppGraph(dataDir)
        val entry = MangaEntry(sourceId = "Test", mangaId = "manga-1", title = "Test Manga")

        graph.library.addToLibrary(entry)
        val items = graph.library.observeLibrary().first()

        assertEquals(1, items.size)
        assertEquals(entry, items.single().entry)
    }

    @Test
    fun secondGraphOnSameDataDirSkipsSchemaCreateAndStillReads() = runBlocking {
        val dataDir = Files.createTempDirectory("app-graph-test")
        val first = AppGraph(dataDir)
        val entry = MangaEntry(sourceId = "Test", mangaId = "manga-1", title = "Test Manga")
        first.library.addToLibrary(entry)

        // constructing a second graph over the same dataDir must not fail (schema-create
        // is skipped because mango.db already exists) and must still see the earlier row
        val second = AppGraph(dataDir)
        val items = second.library.observeLibrary().first()

        assertEquals(1, items.size)
        assertEquals(entry, items.single().entry)
    }

    @Test
    fun aV1DatabaseMigratesInPlaceAndKeepsUserData() = runBlocking {
        val dataDir = Files.createTempDirectory("app-graph-test")
        val first = AppGraph(dataDir)
        val entry = MangaEntry(sourceId = "Test", mangaId = "manga-1", title = "Test Manga")
        first.library.addToLibrary(entry)

        // rewind the db to the v1 shape: drop every column/table added after v1 (v2: download
        // metadata; v3: installed_source version/user_agent; v4: read_progress.finished;
        // v5: library_item.chapter_count, read_progress.chapter_number; v6: details_cache,
        // chapter_cache; v7: chapter_cache.first_seen_at, library_item.last_opened_at), stamp
        // user_version = 1
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dataDir.resolve("mango.db")}", Properties())
        driver.execute(null, "ALTER TABLE download DROP COLUMN manga_title", 0)
        driver.execute(null, "ALTER TABLE download DROP COLUMN chapter_number", 0)
        driver.execute(null, "ALTER TABLE installed_source DROP COLUMN version", 0)
        driver.execute(null, "ALTER TABLE installed_source DROP COLUMN user_agent", 0)
        driver.execute(null, "ALTER TABLE read_progress DROP COLUMN finished", 0)
        driver.execute(null, "ALTER TABLE library_item DROP COLUMN chapter_count", 0)
        driver.execute(null, "ALTER TABLE read_progress DROP COLUMN chapter_number", 0)
        driver.execute(null, "ALTER TABLE library_item DROP COLUMN last_opened_at", 0)
        driver.execute(null, "DROP TABLE details_cache", 0)
        driver.execute(null, "DROP TABLE chapter_cache", 0)
        driver.execute(null, "PRAGMA user_version = 1", 0)
        driver.close()

        // constructing a graph must take the migrate branch, not recreate, keeping the row
        val migrated = AppGraph(dataDir)
        val items = migrated.library.observeLibrary().first()

        assertEquals(entry, items.single().entry)
    }
}
