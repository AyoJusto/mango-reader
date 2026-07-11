package dev.mango.app

import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.Chapter
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

/** In-memory [LibraryRepository] for tests. No DB, no network. */
class FakeLibraryRepository(initial: List<LibraryItem> = emptyList()) : LibraryRepository {
    private val state = MutableStateFlow(initial)

    // Keyed by (sourceId, mangaId, chapterId) — a real in-memory stand-in for persistence,
    // not canned responses, since M3.3's reader reads its own writes (progress round-trips).
    private val progressByChapter = mutableMapOf<Triple<String, String, String>, ReadProgress>()

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

    override suspend fun setProgress(sourceId: String, mangaId: String, chapterId: String, page: Int) {
        progressByChapter[Triple(sourceId, mangaId, chapterId)] =
            ReadProgress(chapterId = chapterId, page = page, updatedAt = Clock.System.now())
    }
}

/** Canned [CatalogRepository] for tests. Unstubbed members throw. No DB, no network. */
class FakeCatalogRepository(
    private val sources: List<SourceInfo> = emptyList(),
    private val results: Map<String, List<MangaEntry>> = emptyMap(),
    private val details: Map<Pair<String, String>, MangaDetails> = emptyMap(),
    private val chapters: Map<Pair<String, String>, List<Chapter>> = emptyMap(),
    private val pages: Map<Triple<String, String, String>, List<Page>> = emptyMap(),
) : CatalogRepository {
    override suspend fun installedSources(): List<SourceInfo> = sources

    override suspend fun install(info: SourceInfo, bundleSha256: String) {
        error("FakeCatalogRepository.install is not stubbed")
    }

    override suspend fun search(sourceId: String, query: String, page: Int): List<MangaEntry> =
        results[query] ?: error("FakeCatalogRepository.search has no canned results for \"$query\"")

    override suspend fun details(sourceId: String, mangaId: String): MangaDetails =
        details[sourceId to mangaId]
            ?: error("FakeCatalogRepository.details has no canned entry for $sourceId/$mangaId")

    override suspend fun chapters(sourceId: String, mangaId: String): List<Chapter> =
        chapters[sourceId to mangaId]
            ?: error("FakeCatalogRepository.chapters has no canned entry for $sourceId/$mangaId")

    override suspend fun pages(sourceId: String, mangaId: String, chapterId: String): List<Page> =
        pages[Triple(sourceId, mangaId, chapterId)]
            ?: error("FakeCatalogRepository.pages has no canned entry for $sourceId/$mangaId/$chapterId")
}
