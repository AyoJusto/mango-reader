package dev.mango.app

import dev.mango.core.domain.AvailableSource
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.ChallengeSolver
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.Download
import dev.mango.core.domain.DownloadManager
import dev.mango.core.domain.DownloadStatus
import dev.mango.core.domain.ExtensionRepo
import dev.mango.core.domain.HomeSection
import dev.mango.core.domain.LibraryItem
import dev.mango.core.domain.LibraryRepository
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.Page
import dev.mango.core.domain.ReadProgress
import dev.mango.core.domain.SourceInfo
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Records solve calls; returns [result] (default false = user didn't pass the challenge). */
class FakeChallengeSolver(private val result: Boolean = false) : ChallengeSolver {
    val solved = mutableListOf<Pair<String, String>>()
    override suspend fun solve(sourceId: String, url: String): Boolean {
        solved += sourceId to url
        return result
    }
}

/** In-memory [LibraryRepository] for tests. No DB, no network. */
class FakeLibraryRepository(initial: List<LibraryItem> = emptyList()) : LibraryRepository {
    private val state = MutableStateFlow(initial)

    // Keyed by (sourceId, mangaId, chapterId) — a real in-memory stand-in for persistence,
    // not canned responses, since the reader reads its own writes (progress round-trips).
    private val progressByChapter = mutableMapOf<Triple<String, String, String>, ReadProgress>()

    // Monotonically increasing stand-in for updated_at, keyed the same way as progressByChapter —
    // matches the SQL ORDER BY updated_at ordering latestProgress() needs, without relying on
    // real clock time (ties/resolution would make ordering flaky in a fast-running test).
    private var writeCounter = 0L
    private val updatedAtOrder = mutableMapOf<Triple<String, String, String>, Long>()

    // Records setChapterCount calls; unlike the real repository, not wired into observeLibrary()
    // (LibraryItem entries here come straight from the constructor list, not a derived query) —
    // the unread-count math itself is covered by LibraryRepositoryTest against the real SQL.
    val chapterCountBySeries = mutableMapOf<Pair<String, String>, Int>()

    override fun observeLibrary(): Flow<List<LibraryItem>> = state

    override suspend fun addToLibrary(entry: MangaEntry) {
        if (state.value.none { it.entry.sourceId == entry.sourceId && it.entry.mangaId == entry.mangaId }) {
            state.value = state.value + LibraryItem(entry, Clock.System.now())
        }
    }

    override suspend fun removeFromLibrary(sourceId: String, mangaId: String) {
        state.value = state.value.filterNot { it.entry.sourceId == sourceId && it.entry.mangaId == mangaId }
    }

    override suspend fun progress(sourceId: String, mangaId: String, chapterId: String): ReadProgress? =
        progressByChapter[Triple(sourceId, mangaId, chapterId)]

    override suspend fun setProgress(
        sourceId: String,
        mangaId: String,
        chapterId: String,
        page: Int,
        finished: Boolean,
        chapterNumber: Double,
    ) {
        val key = Triple(sourceId, mangaId, chapterId)
        val existingFinished = progressByChapter[key]?.finished ?: false
        progressByChapter[key] = ReadProgress(
            chapterId = chapterId,
            page = page,
            updatedAt = Clock.System.now(),
            finished = existingFinished || finished,
            chapterNumber = chapterNumber,
        )
        writeCounter++
        updatedAtOrder[key] = writeCounter
    }

    override suspend fun finishedChapterIds(sourceId: String, mangaId: String): Set<String> =
        progressByChapter.entries
            .filter { it.key.first == sourceId && it.key.second == mangaId && it.value.finished }
            .map { it.key.third }
            .toSet()

    override suspend fun latestProgress(sourceId: String, mangaId: String): ReadProgress? =
        updatedAtOrder.entries
            .filter { it.key.first == sourceId && it.key.second == mangaId }
            .maxByOrNull { it.value }
            ?.let { progressByChapter[it.key] }

    override suspend fun setChapterCount(sourceId: String, mangaId: String, count: Int) {
        chapterCountBySeries[sourceId to mangaId] = count
    }
}

/** Canned [CatalogRepository] for tests. Unstubbed members throw. No DB, no network. */
class FakeCatalogRepository(
    private val sources: List<SourceInfo> = emptyList(),
    private val results: Map<String, List<MangaEntry>> = emptyMap(),
    private val details: Map<Pair<String, String>, MangaDetails> = emptyMap(),
    private val chapters: Map<Pair<String, String>, List<Chapter>> = emptyMap(),
    private val pages: Map<Triple<String, String, String>, List<Page>> = emptyMap(),
    private val sectionsBySource: Map<String, List<HomeSection>> = emptyMap(),
) : CatalogRepository {
    /** How many times [pages] was called — offline-reader tests assert this stays 0. */
    var pagesCallCount: Int = 0
        private set

    /** How many times [details] was called — cache tests assert a hit doesn't call through. */
    var detailsCallCount: Int = 0
        private set

    /** How many times [chapters] was called — cache tests assert a hit doesn't call through. */
    var chaptersCallCount: Int = 0
        private set

    /** Records every [setUserAgent] call, keyed by sourceId — no-op otherwise (no cache to evict). */
    val userAgentsBySourceId = mutableMapOf<String, String>()

    // mutable so install() can behave like the real contract (round-trips into
    // installedSources()) for ExtensionsScreen's flow test; every other caller only ever reads
    // the fixed constructor list, so this changes nothing for them
    private val sourcesState = MutableStateFlow(sources)

    override suspend fun installedSources(): List<SourceInfo> = sourcesState.value

    override suspend fun install(info: SourceInfo, bundleSha256: String) {
        sourcesState.value = sourcesState.value.filterNot { it.sourceId == info.sourceId } + info
    }

    override suspend fun search(sourceId: String, query: String, page: Int): List<MangaEntry> =
        results[query] ?: error("FakeCatalogRepository.search has no canned results for \"$query\"")

    override suspend fun homeSections(sourceId: String): List<HomeSection> =
        sectionsBySource[sourceId] ?: emptyList()

    override suspend fun details(sourceId: String, mangaId: String): MangaDetails {
        detailsCallCount++
        return details[sourceId to mangaId]
            ?: error("FakeCatalogRepository.details has no canned entry for $sourceId/$mangaId")
    }

    override suspend fun chapters(sourceId: String, mangaId: String): List<Chapter> {
        chaptersCallCount++
        return chapters[sourceId to mangaId]
            ?: error("FakeCatalogRepository.chapters has no canned entry for $sourceId/$mangaId")
    }

    override suspend fun pages(sourceId: String, mangaId: String, chapterId: String): List<Page> {
        pagesCallCount++
        return pages[Triple(sourceId, mangaId, chapterId)]
            ?: error("FakeCatalogRepository.pages has no canned entry for $sourceId/$mangaId/$chapterId")
    }

    override suspend fun setUserAgent(sourceId: String, userAgent: String) {
        userAgentsBySourceId[sourceId] = userAgent
    }

    override suspend fun uninstall(sourceId: String) {
        sourcesState.value = sourcesState.value.filterNot { it.sourceId == sourceId }
    }
}

/**
 * In-memory [DownloadManager] for tests. No DB, no HTTP, no disk. [processQueue] is a no-op —
 * these tests only need to observe that a chapter was queued, not that a fake drain occurred.
 *
 * [localPagesByKey] is keyed by "sourceId/mangaId/chapterId"; a missing key means "not
 * downloaded" (null), matching [DownloadManager.localPages]'s contract.
 */
class FakeDownloadManager(
    initial: List<Download> = emptyList(),
    var localPagesByKey: Map<String, List<String>> = emptyMap(),
) : DownloadManager {
    private val state = MutableStateFlow(initial)
    val downloads: List<Download> get() = state.value

    override fun observeDownloads(): Flow<List<Download>> = state

    override suspend fun enqueue(entry: MangaEntry, chapter: Chapter) {
        state.value = state.value + Download(
            sourceId = entry.sourceId,
            mangaId = entry.mangaId,
            chapterId = chapter.chapterId,
            mangaTitle = entry.title,
            chapterNumber = chapter.number,
            status = DownloadStatus.QUEUED,
            pagesTotal = 0,
            pagesDone = 0,
        )
    }

    override suspend fun processQueue() {
        // no-op: nothing to drain in tests
    }

    override suspend fun localPages(sourceId: String, mangaId: String, chapterId: String): List<String>? =
        localPagesByKey["$sourceId/$mangaId/$chapterId"]

    override suspend fun clearDownloads(sourceId: String, mangaId: String) {
        state.value = state.value.filterNot { it.sourceId == sourceId && it.mangaId == mangaId }
        localPagesByKey = localPagesByKey.filterKeys { key ->
            val parts = key.split("/", limit = 3)
            !(parts[0] == sourceId && parts[1] == mangaId)
        }
    }
}

/**
 * Canned [ExtensionRepo] for tests. [install] calls through to [catalog] exactly like the real
 * InkdexRepo does, so a screen that refreshes [CatalogRepository.installedSources] after an
 * install sees the change reflected.
 */
class FakeExtensionRepo(
    private val available: List<AvailableSource> = emptyList(),
    private val catalog: CatalogRepository,
) : ExtensionRepo {
    var installCallCount: Int = 0
        private set

    override suspend fun available(): List<AvailableSource> = available

    override suspend fun install(source: AvailableSource) {
        installCallCount++
        catalog.install(
            SourceInfo(sourceId = source.sourceId, name = source.name, version = source.version),
            bundleSha256 = "fake-sha-${source.sourceId}",
        )
    }
}
