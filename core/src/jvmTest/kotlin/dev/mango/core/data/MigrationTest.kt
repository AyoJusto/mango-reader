package dev.mango.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.mango.core.db.MangoDatabase
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the v1 -> v2 download-table migration (adds manga_title, chapter_number) and the
 * v2 -> v3 installed_source migration (adds version, user_agent) each apply cleanly to a
 * database frozen at the prior shape and preserve existing rows.
 */
class MigrationTest {
    @Test
    fun v1ToV2MigrationPreservesExistingRowsAndAddsTheNewColumns() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        // frozen v1 DDL: the download table's shape before manga_title/chapter_number existed
        driver.execute(
            null,
            """
            CREATE TABLE download (
              source_id TEXT NOT NULL,
              manga_id TEXT NOT NULL,
              chapter_id TEXT NOT NULL,
              status TEXT NOT NULL,
              pages_total INTEGER NOT NULL,
              pages_done INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              PRIMARY KEY(source_id, manga_id, chapter_id)
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            "INSERT INTO download(source_id, manga_id, chapter_id, status, pages_total, pages_done, updated_at) " +
                    "VALUES ('src', 'm1', 'c1', 'DONE', 2, 2, 1000)",
            0,
        )

        // Schema.version is the schema's overall current version, not "whatever this specific
        // migrate(1, 2) call reached" (that would only ever be true while v2 was the latest
        // schema) — so the proof this migration applied is the column data asserted below,
        // not a version-number check.
        MangoDatabase.Schema.migrate(driver, 1, 2)
        val db = MangoDatabase(driver)

        val oldRow = db.downloadsQueries.selectAllDownloads().executeAsList().single()
        assertEquals("src", oldRow.source_id)
        assertEquals("m1", oldRow.manga_id)
        assertEquals("c1", oldRow.chapter_id)
        assertEquals("DONE", oldRow.status)
        assertEquals("", oldRow.manga_title)
        assertEquals(0.0, oldRow.chapter_number)

        db.downloadsQueries.upsertDownload(
            source_id = "src",
            manga_id = "m2",
            chapter_id = "c1",
            manga_title = "Solo Leveling",
            chapter_number = 5.0,
            status = "QUEUED",
            pages_total = 0,
            pages_done = 0,
            updated_at = 2000,
        )
        val newRow = db.downloadsQueries.selectAllDownloads().executeAsList().first { it.manga_id == "m2" }
        assertEquals("Solo Leveling", newRow.manga_title)
        assertEquals(5.0, newRow.chapter_number)
    }

    @Test
    fun v2ToV3MigrationPreservesExistingRowsAndAddsTheNewColumns() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        // frozen v2 DDL: installed_source's shape before version/user_agent existed (unchanged
        // between v1 and v2 — only download changed in the v1 -> v2 migration)
        driver.execute(
            null,
            """
            CREATE TABLE installed_source (
              source_id TEXT NOT NULL PRIMARY KEY,
              name TEXT NOT NULL,
              bundle_sha256 TEXT NOT NULL,
              installed_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            "INSERT INTO installed_source(source_id, name, bundle_sha256, installed_at) " +
                    "VALUES ('FlameComics', 'Flame Comics', 'abc123', 1000)",
            0,
        )

        MangoDatabase.Schema.migrate(driver, 2, 3)

        val db = MangoDatabase(driver)
        val oldRow = db.sourcesQueries.selectInstalledSource("FlameComics").executeAsOne()
        assertEquals("Flame Comics", oldRow.name)
        assertEquals("abc123", oldRow.bundle_sha256)
        assertEquals("", oldRow.version)
        assertEquals(null, oldRow.user_agent)

        db.sourcesQueries.upsertInstalledSource(
            source_id = "MangaBat",
            name = "MangaBat",
            bundle_sha256 = "def456",
            installed_at = 2000,
            version = "1.2.3",
        )
        val newRow = db.sourcesQueries.selectInstalledSource("MangaBat").executeAsOne()
        assertEquals("1.2.3", newRow.version)
    }

    @Test
    fun v6ToV7MigrationPreservesExistingRowsAndAddsTheNewColumns() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        // frozen v6 DDL: chapter_cache and library_item's shape before first_seen_at/last_opened_at existed
        driver.execute(
            null,
            """
            CREATE TABLE chapter_cache (
              source_id TEXT NOT NULL,
              manga_id TEXT NOT NULL,
              chapter_id TEXT NOT NULL,
              number REAL NOT NULL,
              title TEXT,
              published_at INTEGER,
              position INTEGER NOT NULL,
              PRIMARY KEY (source_id, manga_id, chapter_id)
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE library_item (
              source_id TEXT NOT NULL,
              manga_id TEXT NOT NULL,
              title TEXT NOT NULL,
              cover TEXT,
              added_at INTEGER NOT NULL,
              chapter_count INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY (source_id, manga_id)
            )
            """.trimIndent(),
            0,
        )
        // read_progress is untouched by this migration, but selectAllLibraryItems joins it, so it
        // must exist for that query to run at all
        driver.execute(
            null,
            """
            CREATE TABLE read_progress (
              source_id TEXT NOT NULL,
              manga_id TEXT NOT NULL,
              chapter_id TEXT NOT NULL,
              page INTEGER NOT NULL,
              finished INTEGER NOT NULL DEFAULT 0,
              updated_at INTEGER NOT NULL,
              chapter_number REAL NOT NULL DEFAULT 0,
              PRIMARY KEY (source_id, manga_id, chapter_id)
            )
            """.trimIndent(),
            0,
        )
        // collection_member is likewise untouched (it arrives in a later migration), but
        // selectAllLibraryItems reads it via a scalar subquery, so an empty stand-in must exist
        driver.execute(
            null,
            """
            CREATE TABLE collection_member (
              collection_id INTEGER NOT NULL,
              source_id TEXT NOT NULL,
              manga_id TEXT NOT NULL,
              PRIMARY KEY (collection_id, source_id, manga_id)
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            "INSERT INTO chapter_cache(source_id, manga_id, chapter_id, number, title, published_at, position) " +
                    "VALUES ('src', 'm1', 'c1', 1.0, 'Ch. 1', 1000, 0)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO library_item(source_id, manga_id, title, cover, added_at, chapter_count) " +
                    "VALUES ('src', 'm1', 'Solo Leveling', NULL, 500, 3)",
            0,
        )

        MangoDatabase.Schema.migrate(driver, 6, 7)
        val db = MangoDatabase(driver)

        val oldChapterRow = db.catalog_cacheQueries.selectChapterCache("src", "m1").executeAsList().single()
        assertEquals("c1", oldChapterRow.chapter_id)
        assertEquals(0L, oldChapterRow.first_seen_at)

        val oldLibraryRow = db.libraryQueries.selectAllLibraryItems().executeAsList().single()
        assertEquals("Solo Leveling", oldLibraryRow.title)
        assertEquals(3L, oldLibraryRow.chapter_count)

        db.catalog_cacheQueries.insertChapterCache(
            source_id = "src",
            manga_id = "m1",
            chapter_id = "c2",
            number = 2.0,
            title = "Ch. 2",
            published_at = 2000,
            position = 1,
            first_seen_at = 1500,
        )
        val newChapterRow =
            db.catalog_cacheQueries.selectChapterCache("src", "m1").executeAsList().first { it.chapter_id == "c2" }
        assertEquals(1500L, newChapterRow.first_seen_at)

        db.libraryQueries.markOpened(last_opened_at = 999, source_id = "src", manga_id = "m1")
        val touchedLibraryRow = db.libraryQueries.selectAllLibraryItems().executeAsList().single()
        assertEquals(1L, touchedLibraryRow.new_count)
    }

    @Test
    fun v7ToV8MigrationCreatesTheCollectionTablesWithTheSeededDefault() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        // frozen v7 shape: no collection tables existed, and the migration touches nothing
        // else, so no prior DDL is needed for it to apply

        MangoDatabase.Schema.migrate(driver, 7, 8)
        val db = MangoDatabase(driver)

        val seeded = db.collectionsQueries.selectAllCollections().executeAsList().single()
        assertEquals("Reading", seeded.name)
        assertEquals(0L, seeded.position)
        assertEquals(1L, seeded.is_default)

        db.collectionsQueries.insertMember(collection_id = seeded.id, source_id = "src", manga_id = "m1")
        assertEquals(listOf(seeded.id), db.collectionsQueries.selectMembership("src", "m1").executeAsList())
    }

    /** A migrated v1 db and a fresh v3 create must land on the same schema, or drift hides. */
    @Test
    fun migratedSchemaMatchesFreshCreateSchema() {
        val migrated = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        migrated.execute(
            null,
            "CREATE TABLE download (source_id TEXT NOT NULL, manga_id TEXT NOT NULL, " +
                    "chapter_id TEXT NOT NULL, status TEXT NOT NULL, pages_total INTEGER NOT NULL, " +
                    "pages_done INTEGER NOT NULL, updated_at INTEGER NOT NULL, " +
                    "PRIMARY KEY(source_id, manga_id, chapter_id))",
            0,
        )
        migrated.execute(
            null,
            "CREATE TABLE installed_source (source_id TEXT NOT NULL PRIMARY KEY, " +
                    "name TEXT NOT NULL, bundle_sha256 TEXT NOT NULL, installed_at INTEGER NOT NULL)",
            0,
        )
        MangoDatabase.Schema.migrate(migrated, 1, 3)

        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        MangoDatabase.Schema.create(fresh)

        // name -> (type, notnull, default), order-independent: sqldelight maps by name
        fun columns(driver: JdbcSqliteDriver, table: String): Map<String, Triple<String, Long, String?>> =
            driver.executeQuery(
                null,
                "PRAGMA table_info($table)",
                { cursor ->
                    val out = mutableMapOf<String, Triple<String, Long, String?>>()
                    while (cursor.next().value) {
                        out[cursor.getString(1)!!] =
                            Triple(cursor.getString(2)!!, cursor.getLong(3)!!, cursor.getString(4))
                    }
                    app.cash.sqldelight.db.QueryResult.Value(out)
                },
                0,
            ).value

        assertEquals(columns(fresh, "download"), columns(migrated, "download"))
        assertEquals(columns(fresh, "installed_source"), columns(migrated, "installed_source"))
    }
}
