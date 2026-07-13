package dev.mango.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/** In-memory [LibraryRepository] stand-in: only [observeLibrary] and [setChapterCount] are exercised by [LibraryUpdater]. */
private class FakeLibraryRepository(items: List<LibraryItem>) : LibraryRepository {
    private val state = MutableStateFlow(items)
    val chapterCounts = mutableMapOf<Pair<String, String>, Int>()

    override fun observeLibrary(): Flow<List<LibraryItem>> = state
    override suspend fun addToLibrary(entry: MangaEntry) = error("not used by LibraryUpdater")
    override suspend fun removeFromLibrary(sourceId: String, mangaId: String) = error("not used by LibraryUpdater")
    override suspend fun progress(sourceId: String, mangaId: String, chapterId: String): ReadProgress? =
        error("not used by LibraryUpdater")

    override suspend fun setProgress(
        sourceId: String,
        mangaId: String,
        chapterId: String,
        page: Int,
        finished: Boolean,
        chapterNumber: Double,
    ) = error("not used by LibraryUpdater")

    override suspend fun finishedChapterIds(sourceId: String, mangaId: String): Set<String> =
        error("not used by LibraryUpdater")

    override suspend fun latestProgress(sourceId: String, mangaId: String): ReadProgress? =
        error("not used by LibraryUpdater")

    override suspend fun setChapterCount(sourceId: String, mangaId: String, count: Int) {
        chapterCounts[sourceId to mangaId] = count
    }

    override suspend fun markOpened(sourceId: String, mangaId: String) = error("not used by LibraryUpdater")
    override fun observeCollections(): Flow<List<CollectionInfo>> = error("not used by LibraryUpdater")
    override suspend fun createCollection(name: String): Long = error("not used by LibraryUpdater")
    override suspend fun renameCollection(id: Long, name: String) = error("not used by LibraryUpdater")
    override suspend fun deleteCollection(id: Long) = error("not used by LibraryUpdater")
    override suspend fun reorderCollections(orderedIds: List<Long>) = error("not used by LibraryUpdater")
    override suspend fun setDefaultCollection(id: Long) = error("not used by LibraryUpdater")
    override suspend fun setMembership(sourceId: String, mangaId: String, collectionIds: Set<Long>) =
        error("not used by LibraryUpdater")
}

/**
 * Canned [CatalogRepository] stand-in. [chapters] throws for any sourceId in [failingSourceIds],
 * simulating a source that's unreachable for the whole check pass.
 */
private class FakeCatalogRepository(
    private val chaptersBySeries: Map<Pair<String, String>, List<Chapter>>,
    private val detailsBySeries: Map<Pair<String, String>, MangaDetails>,
    private val failingSourceIds: Set<String> = emptySet(),
) : CatalogRepository {
    val detailsCalls = mutableListOf<Pair<String, String>>()
    val chaptersCalls = mutableListOf<Pair<String, String>>()

    override suspend fun installedSources(): List<SourceInfo> = error("not used by LibraryUpdater")
    override suspend fun install(info: SourceInfo, bundleSha256: String) = error("not used by LibraryUpdater")
    override suspend fun search(sourceId: String, query: String, page: Int): List<MangaEntry> =
        error("not used by LibraryUpdater")

    override suspend fun homeSections(sourceId: String): List<HomeSection> = error("not used by LibraryUpdater")

    override suspend fun details(sourceId: String, mangaId: String): MangaDetails {
        detailsCalls += sourceId to mangaId
        return detailsBySeries.getValue(sourceId to mangaId)
    }

    override suspend fun chapters(sourceId: String, mangaId: String): List<Chapter> {
        check(sourceId !in failingSourceIds) { "simulated source failure: $sourceId" }
        chaptersCalls += sourceId to mangaId
        return chaptersBySeries.getValue(sourceId to mangaId)
    }

    override suspend fun pages(sourceId: String, mangaId: String, chapterId: String): List<Page> =
        error("not used by LibraryUpdater")

    override suspend fun setUserAgent(sourceId: String, userAgent: String) = error("not used by LibraryUpdater")
    override suspend fun uninstall(sourceId: String) = error("not used by LibraryUpdater")
}

/** In-memory [CatalogCache] stand-in, seeded with [initial] entries. */
private class FakeCatalogCache(initial: Map<Pair<String, String>, CachedManga> = emptyMap()) : CatalogCache {
    private val entries = initial.toMutableMap()
    val putCalls = mutableListOf<Pair<String, String>>()

    override suspend fun get(sourceId: String, mangaId: String): CachedManga? = entries[sourceId to mangaId]

    override suspend fun put(sourceId: String, mangaId: String, details: MangaDetails, chapters: List<Chapter>) {
        putCalls += sourceId to mangaId
        entries[sourceId to mangaId] = CachedManga(details, chapters)
    }
}

class LibraryUpdaterTest {
    private fun entry(sourceId: String, mangaId: String, title: String = mangaId) =
        MangaEntry(sourceId = sourceId, mangaId = mangaId, title = title)

    private fun details(sourceId: String, mangaId: String) = MangaDetails(
        entry = entry(sourceId, mangaId),
        authors = emptyList(),
        description = null,
        status = MangaStatus.ONGOING,
        tags = emptyList(),
    )

    private fun chapter(id: String) =
        Chapter(chapterId = id, number = id.removePrefix("c").toDouble(), title = null, publishedAt = null)

    @Test
    fun usesCachedDetailsOnHitAndFetchesDetailsOnMiss() = runTest {
        val cachedSeries = "MangaBat" to "m1"
        val missSeries = "MangaBat" to "m2"
        val library = FakeLibraryRepository(
            listOf(
                LibraryItem(entry("MangaBat", "m1"), kotlin.time.Instant.fromEpochMilliseconds(0)),
                LibraryItem(entry("MangaBat", "m2"), kotlin.time.Instant.fromEpochMilliseconds(0))
            ),
        )
        val catalog = FakeCatalogRepository(
            chaptersBySeries = mapOf(cachedSeries to listOf(chapter("c1")), missSeries to listOf(chapter("c1"))),
            detailsBySeries = mapOf(missSeries to details("MangaBat", "m2")),
        )
        val cache = FakeCatalogCache(
            initial = mapOf(cachedSeries to CachedManga(details("MangaBat", "m1"), listOf(chapter("c1")))),
        )
        val updater = LibraryUpdater(catalog, cache, library)

        updater.checkAll()

        assertFalse(cachedSeries in catalog.detailsCalls)
        assertTrue(missSeries in catalog.detailsCalls)
    }

    @Test
    fun everyLibrarySeriesGetsCheckedAndWrittenThrough() = runTest {
        val seriesA = "MangaBat" to "m1"
        val seriesB = "MangaBat" to "m2"
        val library = FakeLibraryRepository(
            listOf(
                LibraryItem(entry("MangaBat", "m1"), kotlin.time.Instant.fromEpochMilliseconds(0)),
                LibraryItem(entry("MangaBat", "m2"), kotlin.time.Instant.fromEpochMilliseconds(0)),
            ),
        )
        val catalog = FakeCatalogRepository(
            chaptersBySeries = mapOf(seriesA to listOf(chapter("c1")), seriesB to listOf(chapter("c1"))),
            detailsBySeries = mapOf(seriesA to details("MangaBat", "m1"), seriesB to details("MangaBat", "m2")),
        )
        val cache = FakeCatalogCache()
        val updater = LibraryUpdater(catalog, cache, library)

        val summary = updater.checkAll()

        assertEquals(setOf(seriesA, seriesB), catalog.chaptersCalls.toSet())
        assertEquals(setOf(seriesA, seriesB), cache.putCalls.toSet())
        assertEquals(setOf(seriesA, seriesB), library.chapterCounts.keys)
        assertEquals(2, summary.seriesChecked)
        assertEquals(0, summary.seriesFailed)
    }

    @Test
    fun oneSourceThrowingMarksOnlyItsSeriesFailedAndOthersStillGetChecked() = runTest {
        val workingSeries = "Toonily" to "m1"
        val failingSeriesOne = "MangaBat" to "m1"
        val failingSeriesTwo = "MangaBat" to "m2"
        val library = FakeLibraryRepository(
            listOf(
                LibraryItem(entry("Toonily", "m1"), kotlin.time.Instant.fromEpochMilliseconds(0)),
                LibraryItem(entry("MangaBat", "m1"), kotlin.time.Instant.fromEpochMilliseconds(0)),
                LibraryItem(entry("MangaBat", "m2"), kotlin.time.Instant.fromEpochMilliseconds(0)),
            ),
        )
        val catalog = FakeCatalogRepository(
            chaptersBySeries = mapOf(
                workingSeries to listOf(chapter("c1")),
                failingSeriesOne to listOf(chapter("c1")),
                failingSeriesTwo to listOf(chapter("c1")),
            ),
            detailsBySeries = mapOf(workingSeries to details("Toonily", "m1")),
            failingSourceIds = setOf("MangaBat"),
        )
        val cache = FakeCatalogCache()
        val updater = LibraryUpdater(catalog, cache, library)

        val summary = updater.checkAll()

        assertEquals(1, summary.seriesChecked)
        assertEquals(2, summary.seriesFailed)
        assertEquals(listOf(workingSeries), cache.putCalls)
    }

    @Test
    fun newChaptersSumsOnlyDiffsAgainstExistingCachesAndFirstFillContributesZero() = runTest {
        val firstFillSeries = "MangaBat" to "m1"
        val existingCacheSeries = "MangaBat" to "m2"
        val library = FakeLibraryRepository(
            listOf(
                LibraryItem(entry("MangaBat", "m1"), kotlin.time.Instant.fromEpochMilliseconds(0)),
                LibraryItem(entry("MangaBat", "m2"), kotlin.time.Instant.fromEpochMilliseconds(0)),
            ),
        )
        val catalog = FakeCatalogRepository(
            chaptersBySeries = mapOf(
                // brand-new series: three live chapters, nothing cached yet
                firstFillSeries to listOf(chapter("c1"), chapter("c2"), chapter("c3")),
                // two of these four chapters are new since the cache below only knows c1/c2
                existingCacheSeries to listOf(chapter("c1"), chapter("c2"), chapter("c3"), chapter("c4")),
            ),
            detailsBySeries = mapOf(firstFillSeries to details("MangaBat", "m1")),
        )
        val cache = FakeCatalogCache(
            initial = mapOf(
                existingCacheSeries to CachedManga(details("MangaBat", "m2"), listOf(chapter("c1"), chapter("c2"))),
            ),
        )
        val updater = LibraryUpdater(catalog, cache, library)

        val summary = updater.checkAll()

        assertEquals(2, summary.newChapters)
    }
}
