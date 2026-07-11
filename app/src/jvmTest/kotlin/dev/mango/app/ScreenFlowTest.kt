package dev.mango.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.MangaStatus
import dev.mango.core.domain.Page
import dev.mango.core.domain.SourceInfo
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * Wraps [FakeCatalogRepository] to count [search] calls. Kept local to this test file rather
 * than added to FakeCatalogRepository itself, which the M3.5c chunk boundary doesn't list as
 * editable.
 */
private class CountingCatalogRepository(private val delegate: CatalogRepository) : CatalogRepository {
    var searchCallCount = 0
        private set

    override suspend fun installedSources(): List<SourceInfo> = delegate.installedSources()

    override suspend fun install(info: SourceInfo, bundleSha256: String) = delegate.install(info, bundleSha256)

    override suspend fun search(sourceId: String, query: String, page: Int): List<MangaEntry> {
        searchCallCount++
        return delegate.search(sourceId, query, page)
    }

    override suspend fun details(sourceId: String, mangaId: String): MangaDetails =
        delegate.details(sourceId, mangaId)

    override suspend fun chapters(sourceId: String, mangaId: String): List<Chapter> =
        delegate.chapters(sourceId, mangaId)

    override suspend fun pages(sourceId: String, mangaId: String, chapterId: String): List<Page> =
        delegate.pages(sourceId, mangaId, chapterId)

    override suspend fun setUserAgent(sourceId: String, userAgent: String) =
        delegate.setUserAgent(sourceId, userAgent)
}

/**
 * End-to-end navigation and interaction flows through [AppShell] and [DetailsScreen], backed
 * by [FakeLibraryRepository]/[FakeCatalogRepository]. CMP 1.11 uses StandardTestDispatcher —
 * every action that launches a coroutine is followed by [waitForIdle] before the next assert.
 */
class ScreenFlowTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun appShellStartsOnEmptyLibraryThenRailSwitchesToBrowse() {
        val library = FakeLibraryRepository()
        val catalog = FakeCatalogRepository(sources = listOf(SourceInfo("FlameComics", "FlameComics")))

        rule.setContent { MangoTheme { AppShell(library, catalog, FakeDownloadManager()) } }

        rule.onNodeWithText("Library is empty — browse sources to add manhwa").assertExists()

        rule.onNodeWithText("Browse").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Search…").assertExists()
    }

    @Test
    fun typingAQueryAndSubmittingShowsCannedResults() {
        val library = FakeLibraryRepository()
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val catalog = FakeCatalogRepository(
            sources = listOf(SourceInfo("FlameComics", "FlameComics")),
            results = mapOf("solo" to listOf(entry)),
        )

        rule.setContent { MangoTheme { AppShell(library, catalog, FakeDownloadManager()) } }

        rule.onNodeWithText("Browse").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Search…").performTextInput("solo")
        rule.waitForIdle()
        rule.onNodeWithText("solo").performImeAction()
        rule.waitForIdle()

        rule.onNodeWithText("Solo Leveling").assertExists()
    }

    @Test
    fun switchingTabsAwayFromBrowseAndBackKeepsQueryAndResultsWithoutResearching() {
        val library = FakeLibraryRepository()
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val fake = FakeCatalogRepository(
            sources = listOf(SourceInfo("FlameComics", "FlameComics")),
            results = mapOf("solo" to listOf(entry)),
        )
        val catalog = CountingCatalogRepository(fake)

        rule.setContent { MangoTheme { AppShell(library, catalog, FakeDownloadManager()) } }

        rule.onNodeWithText("Browse").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Search…").performTextInput("solo")
        rule.waitForIdle()
        rule.onNodeWithText("solo").performImeAction()
        rule.waitForIdle()

        rule.onNodeWithText("Solo Leveling").assertExists()

        rule.onNodeWithText("Library").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Browse").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("solo").assertExists()
        rule.onNodeWithText("Solo Leveling").assertExists()
        assertEquals(1, catalog.searchCallCount)
    }

    @Test
    fun clickingAddToLibraryCallsThroughAndFlipsTheButtonLabel() {
        val library = FakeLibraryRepository()
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val catalog = FakeCatalogRepository(
            details = mapOf(("FlameComics" to "manga-1") to details),
            chapters = mapOf(("FlameComics" to "manga-1") to emptyList()),
        )

        rule.setContent {
            MangoTheme {
                DetailsScreen(
                    sourceId = "FlameComics",
                    mangaId = "manga-1",
                    catalog = catalog,
                    library = library,
                    challengeSolver = FakeChallengeSolver(),
                    onOpenChapter = {},
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Add to library").assertExists()
        rule.onNodeWithText("Add to library").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("In library — remove").assertExists()
    }

    @Test
    fun downloadUnreadEnqueuesOnlyChaptersWithoutProgress() {
        val library = FakeLibraryRepository()
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapters = listOf(
            Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null),
            Chapter(chapterId = "c2", number = 2.0, title = null, publishedAt = null),
        )
        val catalog = FakeCatalogRepository(
            details = mapOf(("FlameComics" to "manga-1") to details),
            chapters = mapOf(("FlameComics" to "manga-1") to chapters),
        )
        runBlocking { library.setProgress("FlameComics", "manga-1", "c1", page = 3) }
        var downloaded: List<Chapter>? = null

        rule.setContent {
            MangoTheme {
                DetailsScreen(
                    sourceId = "FlameComics",
                    mangaId = "manga-1",
                    catalog = catalog,
                    library = library,
                    challengeSolver = FakeChallengeSolver(),
                    onOpenChapter = {},
                    onDownloadAll = { _, chs -> downloaded = chs },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Download unread").performClick()
        rule.waitForIdle()

        assertEquals(listOf("c2"), downloaded?.map { it.chapterId })
    }

    @Test
    fun downloadRangeFiltersChaptersByNumberInclusive() {
        val library = FakeLibraryRepository()
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapters = (1..5).map { n ->
            Chapter(chapterId = "c$n", number = n.toDouble(), title = null, publishedAt = null)
        }
        val catalog = FakeCatalogRepository(
            details = mapOf(("FlameComics" to "manga-1") to details),
            chapters = mapOf(("FlameComics" to "manga-1") to chapters),
        )
        var downloaded: List<Chapter>? = null

        rule.setContent {
            MangoTheme {
                DetailsScreen(
                    sourceId = "FlameComics",
                    mangaId = "manga-1",
                    catalog = catalog,
                    library = library,
                    challengeSolver = FakeChallengeSolver(),
                    onOpenChapter = {},
                    onDownloadAll = { _, chs -> downloaded = chs },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Download range…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("From").performTextInput("2")
        rule.onNodeWithText("To").performTextInput("4")
        rule.waitForIdle()
        rule.onNodeWithText("Download").performClick()
        rule.waitForIdle()

        assertEquals(listOf("c2", "c3", "c4"), downloaded?.map { it.chapterId })
    }
}
