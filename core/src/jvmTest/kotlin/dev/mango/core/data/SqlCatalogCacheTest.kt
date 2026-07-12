package dev.mango.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.mango.core.db.MangoDatabase
import dev.mango.core.domain.CatalogCache
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.MangaStatus
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/** Integration tests for [SqlCatalogCache], driven only through the [CatalogCache] contract. */
class SqlCatalogCacheTest {
    private fun newCache(): CatalogCache {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        MangoDatabase.Schema.create(driver)
        return SqlCatalogCache(MangoDatabase(driver))
    }

    private val entry = MangaEntry(sourceId = "MangaBat", mangaId = "m1", title = "Solo Leveling", cover = "https://x/cover.jpg")
    private val details = MangaDetails(
        entry = entry,
        authors = listOf("Chugong", "Redice Studio"),
        description = "A weak hunter becomes strong.",
        status = MangaStatus.ONGOING,
        tags = listOf("Action", "Fantasy"),
    )
    private val chapters = listOf(
        Chapter(chapterId = "c1", number = 1.0, title = "Ch. 1", publishedAt = Instant.fromEpochMilliseconds(1_000)),
        Chapter(chapterId = "c2", number = 2.0, title = "Ch. 2", publishedAt = Instant.fromEpochMilliseconds(2_000)),
    )

    @Test
    fun getOnUnknownMangaReturnsNull() = runTest {
        val cache = newCache()
        assertNull(cache.get("MangaBat", "no-such-manga"))
    }

    @Test
    fun putThenGetRoundTripsEveryField() = runTest {
        val cache = newCache()

        cache.put("MangaBat", "m1", details, chapters)
        val cached = cache.get("MangaBat", "m1")

        assertEquals(details, cached?.details)
        assertEquals(chapters, cached?.chapters)
    }

    @Test
    fun putThenGetRoundTripsNullableCoverTitleAndPublishedAt() = runTest {
        val cache = newCache()
        val bareDetails = details.copy(entry = entry.copy(cover = null))
        val bareChapters = listOf(Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null))

        cache.put("MangaBat", "m1", bareDetails, bareChapters)
        val cached = cache.get("MangaBat", "m1")

        assertNull(cached?.details?.entry?.cover)
        assertNull(cached?.chapters?.single()?.title)
        assertNull(cached?.chapters?.single()?.publishedAt)
    }

    @Test
    fun rePutWithAShorterChapterListLeavesExactlyTheNewSet() = runTest {
        val cache = newCache()
        cache.put("MangaBat", "m1", details, chapters)

        val shorter = listOf(chapters[0])
        cache.put("MangaBat", "m1", details, shorter)

        assertEquals(shorter, cache.get("MangaBat", "m1")?.chapters)
    }

    @Test
    fun rePutUpdatesDetailsFields() = runTest {
        val cache = newCache()
        cache.put("MangaBat", "m1", details, chapters)

        val updated = details.copy(
            entry = entry.copy(title = "Solo Leveling (renamed)"),
            description = "Updated synopsis.",
            status = MangaStatus.COMPLETED,
        )
        cache.put("MangaBat", "m1", updated, chapters)

        val cached = cache.get("MangaBat", "m1")
        assertEquals("Solo Leveling (renamed)", cached?.details?.entry?.title)
        assertEquals("Updated synopsis.", cached?.details?.description)
        assertEquals(MangaStatus.COMPLETED, cached?.details?.status)
    }

    @Test
    fun sameMangaIdUnderTwoSourcesStaysIsolated() = runTest {
        val cache = newCache()

        cache.put("MangaBat", "m1", details, chapters)
        cache.put("Toonily", "m1", details.copy(entry = entry.copy(title = "Different Title")), listOf(chapters[0]))

        assertEquals("Solo Leveling", cache.get("MangaBat", "m1")?.details?.entry?.title)
        assertEquals("Different Title", cache.get("Toonily", "m1")?.details?.entry?.title)
        assertEquals(2, cache.get("MangaBat", "m1")?.chapters?.size)
        assertEquals(1, cache.get("Toonily", "m1")?.chapters?.size)
    }

    @Test
    fun getOnAMangaWithDetailsButNoChaptersReturnsAnEmptyChapterList() = runTest {
        val cache = newCache()

        cache.put("MangaBat", "m1", details, emptyList())

        assertEquals(emptyList(), cache.get("MangaBat", "m1")?.chapters)
    }
}
