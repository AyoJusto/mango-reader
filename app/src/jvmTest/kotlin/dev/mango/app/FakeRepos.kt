package dev.mango.app

import dev.mango.core.domain.AvailableSource
import dev.mango.core.domain.CachedManga
import dev.mango.core.domain.CatalogCache
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.ChallengeSolver
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.CollectionInfo
import dev.mango.core.domain.CookieStore
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
import dev.mango.core.domain.StoredCookie
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class NoOpCookieStore : CookieStore {
    override suspend fun cookiesFor(host: String): List<StoredCookie> = emptyList()
    override suspend fun put(cookie: StoredCookie) = Unit
}

class FakeChallengeSolver(private val result: Boolean = false) : ChallengeSolver {
    val solved = mutableListOf<Pair<String, String>>()
    override suspend fun solve(sourceId: String, url: String): Boolean {
        solved += sourceId to url
        return result
    }
}

class FakeLibraryRepository(initial: List<LibraryItem> = emptyList()) : LibraryRepository {
    private val state = MutableStateFlow(initial)

    private val collections = MutableStateFlow(listOf(CollectionInfo(1, "Reading", 0, true)))
    private val membership = mutableMapOf<Pair<String, String>, Set<Long>>()

    init {
        initial.forEach { membership[it.entry.sourceId to it.entry.mangaId] = it.collectionIds }
    }

    private val progressByChapter = mutableMapOf<Triple<String, String, String>, ReadProgress>()

    // Counter instead of wall-clock time: ties would make ordering flaky.
    private var writeCounter = 0L
    private val updatedAtOrder = mutableMapOf<Triple<String, String, String>, Long>()

    val chapterCountBySeries = mutableMapOf<Pair<String, String>, Int>()

    val openedAt = mutableMapOf<Pair<String, String>, Long>()

    override fun observeLibrary(): Flow<List<LibraryItem>> = state

    override suspend fun addToLibrary(entry: MangaEntry) {
        if (state.value.none { it.entry.sourceId == entry.sourceId && it.entry.mangaId == entry.mangaId }) {
            membership[entry.sourceId to entry.mangaId] = setOf(collections.value.first { it.isDefault }.id)
            state.value = state.value + LibraryItem(entry, Clock.System.now())
            reflectMembership()
        }
    }

    override suspend fun removeFromLibrary(sourceId: String, mangaId: String) {
        membership.remove(sourceId to mangaId)
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

    override suspend fun markOpened(sourceId: String, mangaId: String) {
        openedAt[sourceId to mangaId] = Clock.System.now().toEpochMilliseconds()
    }

    override fun observeCollections(): Flow<List<CollectionInfo>> = collections

    override suspend fun createCollection(name: String): Long {
        require(collections.value.none { it.name == name }) { "collection \"$name\" already exists" }
        val id = (collections.value.maxOfOrNull { it.id } ?: 0L) + 1
        val position = (collections.value.maxOfOrNull { it.position } ?: -1) + 1
        collections.value = collections.value + CollectionInfo(id, name, position, isDefault = false)
        return id
    }

    override suspend fun renameCollection(id: Long, name: String) {
        require(collections.value.none { it.name == name && it.id != id }) { "collection \"$name\" already exists" }
        collections.value = collections.value.map { if (it.id == id) it.copy(name = name) else it }
    }

    override suspend fun deleteCollection(id: Long) {
        check(collections.value.size > 1) { "cannot delete the last collection" }
        val wasDefault = collections.value.first { it.id == id }.isDefault
        var remaining = collections.value.filterNot { it.id == id }
        if (wasDefault) {
            val promotedId = remaining.minBy { it.position }.id
            remaining = remaining.map { it.copy(isDefault = it.id == promotedId) }
        }
        collections.value = remaining
        membership.entries.forEach { it.setValue(it.value - id) }
        reflectMembership()
    }

    override suspend fun reorderCollections(orderedIds: List<Long>) {
        val byId = collections.value.associateBy { it.id }
        collections.value = orderedIds.mapIndexed { index, id -> byId.getValue(id).copy(position = index) }
    }

    override suspend fun setDefaultCollection(id: Long) {
        collections.value = collections.value.map { it.copy(isDefault = it.id == id) }
    }

    override suspend fun setMembership(sourceId: String, mangaId: String, collectionIds: Set<Long>) {
        membership[sourceId to mangaId] = collectionIds
        reflectMembership()
    }

    private fun reflectMembership() {
        state.value = state.value.map {
            it.copy(collectionIds = membership[it.entry.sourceId to it.entry.mangaId].orEmpty())
        }
    }
}

class FakeCatalogRepository(
    private val sources: List<SourceInfo> = emptyList(),
    private val results: Map<String, List<MangaEntry>> = emptyMap(),
    private val details: Map<Pair<String, String>, MangaDetails> = emptyMap(),
    private val chapters: Map<Pair<String, String>, List<Chapter>> = emptyMap(),
    private val pages: Map<Triple<String, String, String>, List<Page>> = emptyMap(),
    private val sectionsBySource: Map<String, List<HomeSection>> = emptyMap(),
) : CatalogRepository {
    var pagesCallCount: Int = 0
        private set

    var detailsCallCount: Int = 0
        private set

    var chaptersCallCount: Int = 0
        private set

    val userAgentsBySourceId = mutableMapOf<String, String>()

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

class FakeCatalogCache(initial: Map<Pair<String, String>, CachedManga> = emptyMap()) : CatalogCache {
    private val entries = initial.toMutableMap()
    private var stampCounter = 0L

    var putCount: Int = 0
        private set

    override suspend fun get(sourceId: String, mangaId: String): CachedManga? = entries[sourceId to mangaId]

    override suspend fun put(sourceId: String, mangaId: String, details: MangaDetails, chapters: List<Chapter>) {
        putCount++
        val existing = entries[sourceId to mangaId]
        val previousFirstSeenAt = existing?.firstSeenAt.orEmpty()
        val isFirstFill = previousFirstSeenAt.isEmpty()
        val stamp = Instant.fromEpochMilliseconds(++stampCounter)
        val firstSeenAt = chapters.associate { chapter ->
            chapter.chapterId to when {
                isFirstFill -> Instant.fromEpochMilliseconds(0)
                else -> previousFirstSeenAt[chapter.chapterId] ?: stamp
            }
        }
        entries[sourceId to mangaId] = CachedManga(details, chapters, checkedAt = stamp, firstSeenAt = firstSeenAt)
    }
}

internal class CountingCatalogRepository(private val delegate: CatalogRepository) : CatalogRepository {
    var searchCallCount = 0
        private set

    override suspend fun installedSources(): List<SourceInfo> = delegate.installedSources()

    override suspend fun install(info: SourceInfo, bundleSha256: String) = delegate.install(info, bundleSha256)

    override suspend fun search(sourceId: String, query: String, page: Int): List<MangaEntry> {
        searchCallCount++
        return delegate.search(sourceId, query, page)
    }

    override suspend fun homeSections(sourceId: String): List<HomeSection> =
        delegate.homeSections(sourceId)

    override suspend fun details(sourceId: String, mangaId: String): MangaDetails =
        delegate.details(sourceId, mangaId)

    override suspend fun chapters(sourceId: String, mangaId: String): List<Chapter> =
        delegate.chapters(sourceId, mangaId)

    override suspend fun pages(sourceId: String, mangaId: String, chapterId: String): List<Page> =
        delegate.pages(sourceId, mangaId, chapterId)

    override suspend fun setUserAgent(sourceId: String, userAgent: String) =
        delegate.setUserAgent(sourceId, userAgent)

    override suspend fun uninstall(sourceId: String) = delegate.uninstall(sourceId)
}

internal class SuspendingCatalogRepository(private val delegate: CatalogRepository) : CatalogRepository by delegate {
    override suspend fun details(sourceId: String, mangaId: String): MangaDetails = awaitCancellation()
}

internal class ThrowingCatalogRepository(
    private val delegate: CatalogRepository,
    private val exception: Exception,
) : CatalogRepository by delegate {
    override suspend fun details(sourceId: String, mangaId: String): MangaDetails = throw exception
}

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
