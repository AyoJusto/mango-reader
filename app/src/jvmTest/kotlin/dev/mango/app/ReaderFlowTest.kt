package dev.mango.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.HomeSection
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.Page
import dev.mango.core.domain.SourceInfo
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

private const val SOURCE_ID = "FlameComics"
private const val MANGA_ID = "manga-1"
private const val CHAPTER_ID = "ch-1"
private const val CHAPTER_2_ID = "ch-2"

/**
 * Wraps a [CatalogRepository] so a specific chapter's [pages] call always throws — used only by
 * [ReaderFlowTest.retryAppearsAndTheLoadedChapterSurvivesWhenTheNextChapterFailsToLoad], same
 * scripted-delegate style as [BrowseFlowTest]'s SectionCountingCatalogRepository.
 */
private class FailingPagesCatalogRepository(
    private val delegate: CatalogRepository,
    private val failingChapterId: String,
) : CatalogRepository {
    override suspend fun installedSources(): List<SourceInfo> = delegate.installedSources()
    override suspend fun install(info: SourceInfo, bundleSha256: String) = delegate.install(info, bundleSha256)
    override suspend fun search(sourceId: String, query: String, page: Int): List<MangaEntry> =
        delegate.search(sourceId, query, page)
    override suspend fun homeSections(sourceId: String): List<HomeSection> = delegate.homeSections(sourceId)
    override suspend fun details(sourceId: String, mangaId: String): MangaDetails = delegate.details(sourceId, mangaId)
    override suspend fun chapters(sourceId: String, mangaId: String): List<Chapter> = delegate.chapters(sourceId, mangaId)
    override suspend fun pages(sourceId: String, mangaId: String, chapterId: String): List<Page> {
        if (chapterId == failingChapterId) error("scripted failure for $chapterId")
        return delegate.pages(sourceId, mangaId, chapterId)
    }
    override suspend fun setUserAgent(sourceId: String, userAgent: String) = delegate.setUserAgent(sourceId, userAgent)
}

/**
 * End-to-end input, chapter-navigation, and progress-persistence flows through [ReaderScreen],
 * backed by [FakeLibraryRepository]/[FakeCatalogRepository]. Page content is a colored
 * placeholder box (not Coil) so these run offscreen with no network. CMP 1.11 uses
 * StandardTestDispatcher — every action that launches a coroutine is followed by [waitForIdle]
 * before the next assert.
 */
class ReaderFlowTest {
    @get:Rule
    val rule = createComposeRule()

    private val fakePages = (0 until 5).map { index -> Page(index = index, url = "https://example.test/$index.jpg") }
    private val chapter2Pages = (0 until 3).map { index -> Page(index = index, url = "https://example.test/ch2-$index.jpg") }
    private val twoChapters = listOf(Chapter(CHAPTER_ID, number = 1.0), Chapter(CHAPTER_2_ID, number = 2.0))

    @Composable
    private fun FakePageContent(page: Page) {
        Box(
            modifier = Modifier.fillMaxWidth().height(500.dp).background(Color(0xFF3A4A5A)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "${page.index + 1}", style = MaterialTheme.typography.labelLarge)
        }
    }

    private fun catalogWithPages() = FakeCatalogRepository(
        sources = listOf(SourceInfo(SOURCE_ID, SOURCE_ID)),
        pages = mapOf(
            Triple(SOURCE_ID, MANGA_ID, CHAPTER_ID) to fakePages,
            Triple(SOURCE_ID, MANGA_ID, CHAPTER_2_ID) to chapter2Pages,
        ),
    )

    private fun setReaderContent(
        library: FakeLibraryRepository,
        catalog: CatalogRepository,
        downloads: FakeDownloadManager = FakeDownloadManager(),
        progressDebounceMillis: Long = 500,
        chapterId: String = CHAPTER_ID,
        chapters: List<Chapter> = listOf(Chapter(CHAPTER_ID, number = 1.0)),
    ) {
        rule.setContent {
            MangoTheme {
                ReaderScreen(
                    sourceId = SOURCE_ID,
                    mangaId = MANGA_ID,
                    chapterId = chapterId,
                    chapters = chapters,
                    catalog = catalog,
                    downloads = downloads,
                    library = library,
                    challengeSolver = FakeChallengeSolver(),
                    onBack = {},
                    onToggleFullscreen = {},
                    progressDebounceMillis = progressDebounceMillis,
                    pageContent = { page -> FakePageContent(page) },
                )
            }
        }
    }

    /** Repeated PageDown+waitForIdle until [predicate] is true, or gives up after a generous cap. */
    private fun scrollUntil(predicate: () -> Boolean) {
        repeat(40) {
            if (predicate()) return
            rule.onRoot().performKeyInput { pressKey(Key.PageDown) }
            rule.waitForIdle()
        }
    }

    private fun textVisible(text: String): Boolean =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun pageDownScrollsForward() {
        val library = FakeLibraryRepository()
        setReaderContent(library, catalogWithPages())
        rule.waitForIdle()

        rule.onNodeWithText("1 / 5").assertExists()

        rule.onRoot().performKeyInput { pressKey(Key.PageDown) }
        rule.waitForIdle()

        rule.onNodeWithText("1 / 5").assertDoesNotExist()
    }

    @Test
    fun scrollingRecordsProgressAfterDebounce() {
        // debounce(500)'s delay runs on kotlinx.coroutines virtual time (a TestCoroutineScheduler),
        // which is a different clock from rule.mainClock (Compose's frame clock, used to advance
        // animateScrollBy/withFrameNanos). advanceTimeBy on the latter doesn't resolve the former,
        // so neither waitForIdle() nor manual mainClock advancement reliably lands inside the
        // debounce window here. Per the chunk spec's documented fallback, the debounce is made
        // injectable and this test passes 0 for a deterministic write instead of racing clocks.
        val library = FakeLibraryRepository()
        setReaderContent(library, catalogWithPages(), progressDebounceMillis = 0)
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.PageDown) }
        rule.waitForIdle()

        val progress = runBlocking { library.progress(SOURCE_ID, MANGA_ID, CHAPTER_ID) }
        assertNotNull(progress, "expected progress to be recorded after scrolling")
    }

    @Test
    fun resumesAtSavedProgress() {
        val library = FakeLibraryRepository()
        runBlocking { library.setProgress(SOURCE_ID, MANGA_ID, CHAPTER_ID, page = 3) }
        setReaderContent(library, catalogWithPages())
        rule.waitForIdle()

        rule.onNodeWithText("4 / 5").assertExists()
    }

    @Test
    fun rendersLocalPagesWithoutHittingTheCatalogWhenTheChapterIsDownloaded() {
        val dir = Files.createTempDirectory("reader-offline-test")
        val file0 = dir.resolve("0000.jpg").also { Files.write(it, byteArrayOf(1)) }
        val file1 = dir.resolve("0001.jpg").also { Files.write(it, byteArrayOf(2)) }
        val library = FakeLibraryRepository()
        val catalog = catalogWithPages()
        val downloads = FakeDownloadManager(
            localPagesByKey = mapOf("$SOURCE_ID/$MANGA_ID/$CHAPTER_ID" to listOf(file0.toString(), file1.toString())),
        )

        setReaderContent(library, catalog, downloads = downloads)
        rule.waitForIdle()

        rule.onNodeWithText("1 / 2 · offline").assertExists()
        assertEquals(0, catalog.pagesCallCount)
    }

    @Test
    fun fallsBackToTheCatalogWhenTheChapterIsNotDownloaded() {
        val library = FakeLibraryRepository()
        val catalog = catalogWithPages()

        // Default FakeDownloadManager has no localPagesByKey entries, so localPages() returns
        // null and the reader must fall back to the live catalog, same as before this chunk.
        setReaderContent(library, catalog)
        rule.waitForIdle()

        rule.onNodeWithText("1 / 5").assertExists()
        assertEquals(1, catalog.pagesCallCount)
    }

    @Test
    fun scrollingPastChapterEndAutoAppendsTheNextChapter() {
        val library = FakeLibraryRepository()
        val catalog = catalogWithPages()
        setReaderContent(library, catalog, chapters = twoChapters)
        rule.waitForIdle()

        scrollUntil { textVisible("Ch. 2") }

        assertTrue(textVisible("Ch. 2"), "expected ch-2's divider or counter to become visible")
        assertEquals(2, catalog.pagesCallCount)
    }

    @Test
    fun crossingIntoTheNextChapterRecordsItsProgress() {
        val library = FakeLibraryRepository()
        val catalog = catalogWithPages()
        setReaderContent(
            library,
            catalog,
            chapters = twoChapters,
            progressDebounceMillis = 0,
        )
        rule.waitForIdle()

        // "Ch. 2" alone isn't enough: it also appears in the divider ("Ch. 1 — end · Ch. 2"),
        // which still attributes to ch-1. The " / 3" counter denominator is unique to ch-2's
        // pages, so it only appears once the strip has actually scrolled past the divider.
        scrollUntil { textVisible("/ 3") }
        rule.waitForIdle()

        val progress = runBlocking { library.progress(SOURCE_ID, MANGA_ID, CHAPTER_2_ID) }
        assertNotNull(progress, "expected ch-2 progress once the strip has scrolled into it")
    }

    @Test
    fun keyNAdvancesToTheNextChapter() {
        val library = FakeLibraryRepository()
        val catalog = catalogWithPages()
        setReaderContent(library, catalog, chapters = twoChapters)
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.N) }
        rule.waitForIdle()
        rule.waitForIdle()

        assertTrue(textVisible("Ch. 2"), "expected N to advance the reader into ch-2")
    }

    @Test
    fun keyPReturnsToThePreviousChapter() {
        val library = FakeLibraryRepository()
        val catalog = catalogWithPages()
        setReaderContent(
            library,
            catalog,
            chapterId = CHAPTER_2_ID,
            chapters = twoChapters,
        )
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.P) }
        rule.waitForIdle()
        rule.waitForIdle()

        assertTrue(textVisible("Ch. 1"), "expected P to re-anchor the reader at ch-1")
    }

    @Test
    fun lastChapterEndShowsNoMoreChapters() {
        val library = FakeLibraryRepository()
        val catalog = catalogWithPages()
        setReaderContent(
            library,
            catalog,
            chapterId = CHAPTER_2_ID,
            chapters = twoChapters,
        )
        rule.waitForIdle()

        scrollUntil { textVisible("No more chapters") }

        assertTrue(textVisible("No more chapters"), "expected the end-of-strip footer at the last chapter")
    }

    @Test
    fun retryAppearsAndTheLoadedChapterSurvivesWhenTheNextChapterFailsToLoad() {
        val library = FakeLibraryRepository()
        val catalog = FailingPagesCatalogRepository(catalogWithPages(), failingChapterId = CHAPTER_2_ID)
        setReaderContent(library, catalog, chapters = twoChapters)
        rule.waitForIdle()

        scrollUntil { textVisible("Retry") }

        assertTrue(textVisible("Retry"), "expected a Retry row once the next chapter's load fails")
        // "/ 5" is ch-1's counter denominator (ch-2's is "/ 3") — proves ch-1 is still current.
        assertTrue(textVisible("/ 5"), "expected ch-1 to still be the current chapter after the failed append")
    }
}
