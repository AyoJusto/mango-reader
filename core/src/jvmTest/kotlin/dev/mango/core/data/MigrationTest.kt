package dev.mango.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.mango.core.db.MangoDatabase
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the v1 -> v2 download-table migration (adds manga_title, chapter_number) applies
 * cleanly to a database frozen at the v1 shape and preserves existing rows.
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

        MangoDatabase.Schema.migrate(driver, 1, 2)

        assertEquals(2, MangoDatabase.Schema.version)
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

    /** A migrated v1 db and a fresh v2 create must land on the same schema, or drift hides. */
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
        MangoDatabase.Schema.migrate(migrated, 1, 2)

        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        MangoDatabase.Schema.create(fresh)

        // name -> (type, notnull, default), order-independent: sqldelight maps by name
        fun columns(driver: JdbcSqliteDriver): Map<String, Triple<String, Long, String?>> =
            driver.executeQuery(
                null,
                "PRAGMA table_info(download)",
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

        assertEquals(columns(fresh), columns(migrated))
    }
}
