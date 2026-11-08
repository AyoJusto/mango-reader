package dev.mango.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.HomeSection
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.Page
import dev.mango.core.domain.SourceHeaderPolicy
import dev.mango.core.domain.SourceInfo
import dev.mango.core.engine.DefaultSourceHeaderPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
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
    override suspend fun chapters(sourceId: String, mangaId: String): List<Chapter> =
        delegate.chapters(sourceId, mangaId)

    override suspend fun pages(sourceId: String, mangaId: String, chapterId: String): List<Page> {
        if (chapterId == failingChapterId) error("scripted failure for $chapterId")
        return delegate.pages(sourceId, mangaId, chapterId)
    }

    override suspend fun setUserAgent(sourceId: String, userAgent: String) = delegate.setUserAgent(sourceId, userAgent)
    override suspend fun uninstall(sourceId: String) = delegate.uninstall(sourceId)
}

private class UrlRewritingHeaderPolicy(private val suffix: String) : SourceHeaderPolicy {
    override suspend fun headersFor(sourceId: String, url: String, headers: Map<String, String>): Map<String, String> =
        headers

    override suspend fun withPolicyHeaders(sourceId: String, pages: List<Page>): List<Page> =
        pages.map { it.copy(url = it.url + suffix) }
}

class ReaderFlowTest {
    @get:Rule
    val rule = createComposeRule()

    private val fakePages = (0 until 5).map { index -> Page(index = index, url = "https://example.test/$index.jpg") }
    private val chapter2Pages =
        (0 until 3).map { index -> Page(index = index, url = "https://example.test/ch2-$index.jpg") }
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
        controlsAutoHideMillis: Long = 2000,
        autoScrollSpeedDpPerSec: Float = 120f,
        chapterId: String = CHAPTER_ID,
        chapters: List<Chapter> = listOf(Chapter(CHAPTER_ID, number = 1.0)),
    ) {
        rule.setContent {
            ProvideMangoTheme(MangoDark) {
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
                    controlsAutoHideMillis = controlsAutoHideMillis,
                    autoScrollSpeedDpPerSec = autoScrollSpeedDpPerSec,
                    pageContent = { page -> FakePageContent(page) },
                )
            }
        }
    }

    private fun currentPageCounter(): String? = (1..5).map { "Page $it of 5" }.firstOrNull { descriptionVisible(it) }

    private fun scrollUntil(predicate: () -> Boolean) {
        repeat(40) {
            if (predicate()) return
            rule.onRoot().performKeyInput { pressKey(Key.PageDown) }
            rule.waitForIdle()
        }
    }

    private fun descriptionVisible(description: String): Boolean =
        rule.onAllNodesWithContentDescription(description, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun textVisible(text: String): Boolean =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun pageDownScrollsForward() {
        val library = FakeLibraryRepository()
        setReaderContent(library, catalogWithPages())
        rule.waitForIdle()

        rule.onNodeWithContentDescription("Page 1 of 5").assertExists()

        rule.onRoot().performKeyInput { pressKey(Key.PageDown) }
        rule.waitForIdle()

        rule.onNodeWithContentDescription("Page 1 of 5").assertDoesNotExist()
    }

    @Test
    fun barShowsNoPanelCount() {
        setReaderContent(FakeLibraryRepository(), catalogWithPages())
        rule.waitForIdle()

        rule.onNodeWithContentDescription("Page 1 of 5").assertExists()
        rule.onNodeWithText("panels", substring = true).assertDoesNotExist()
        rule.onNodeWithText("offline").assertDoesNotExist()
    }

    @Test
    fun scrollingRecordsProgressAfterDebounce() {
        // debounce runs on coroutine virtual time, not mainClock; pass 0 to avoid racing clocks.
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

        rule.onNodeWithContentDescription("Page 4 of 5").assertExists()
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

        rule.onNodeWithContentDescription("Page 1 of 2").assertExists()
        rule.onNodeWithText("offline").assertExists()
        assertEquals(0, catalog.pagesCallCount)
    }

    @Test
    fun fallsBackToTheCatalogWhenTheChapterIsNotDownloaded() {
        val library = FakeLibraryRepository()
        val catalog = catalogWithPages()

        setReaderContent(library, catalog)
        rule.waitForIdle()

        rule.onNodeWithContentDescription("Page 1 of 5").assertExists()
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

        scrollUntil { descriptionVisible("of 3") }
        rule.waitForIdle()

        val progress = runBlocking { library.progress(SOURCE_ID, MANGA_ID, CHAPTER_2_ID) }
        assertNotNull(progress, "expected ch-2 progress once the strip has scrolled into it")
    }

    @Test
    fun scrollingToTheEndOfAChapterMarksItFinished() {
        val library = FakeLibraryRepository()
        val catalog = catalogWithPages()
        setReaderContent(library, catalog, chapters = twoChapters, progressDebounceMillis = 0)
        rule.waitForIdle()

        scrollUntil { textVisible("Ch. 2") }
        rule.waitForIdle()

        val progress = runBlocking { library.progress(SOURCE_ID, MANGA_ID, CHAPTER_ID) }
        assertTrue(
            progress?.finished == true,
            "expected ch-1 to be marked finished once its divider scrolled into view"
        )
    }

    @Test
    fun aSinglePageDownWritesProgressButDoesNotMarkTheChapterFinished() {
        val library = FakeLibraryRepository()
        setReaderContent(library, catalogWithPages(), progressDebounceMillis = 0)
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.PageDown) }
        rule.waitForIdle()

        val progress = runBlocking { library.progress(SOURCE_ID, MANGA_ID, CHAPTER_ID) }
        assertTrue(
            progress != null && !progress.finished,
            "expected a single PageDown to record progress without finishing the chapter"
        )
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
        assertTrue(descriptionVisible("of 5"), "expected ch-1 to still be the current chapter after the failed append")
    }

    @Test
    fun controlsRevealOnGenuineMovementNotSubPixelJitterOrClick() {
        // autoAdvance stays false: a parked cursor makes waitForIdle diverge via hover-Move show/hide cycles.
        val library = FakeLibraryRepository()
        setReaderContent(library, catalogWithPages(), controlsAutoHideMillis = 10_000)
        rule.waitForIdle()

        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(11_000)
        rule.onNodeWithText("Ch. 1").assertDoesNotExist()

        rule.onRoot().performMouseInput { moveTo(Offset(400f, 400f)) }
        rule.mainClock.advanceTimeBy(11_000)
        rule.onNodeWithText("Ch. 1").assertDoesNotExist()

        rule.onRoot().performMouseInput { moveTo(Offset(401f, 400f)) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Ch. 1").assertDoesNotExist()

        rule.onRoot().performMouseInput { moveTo(Offset(401f, 450f)) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Ch. 1").assertExists()

        rule.mainClock.advanceTimeBy(11_000)
        rule.onNodeWithText("Ch. 1").assertDoesNotExist()

        rule.onRoot().performMouseInput { click(Offset(400f, 400f)) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Ch. 1").assertExists()

        rule.onRoot().performMouseInput { click(Offset(400f, 400f)) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Ch. 1").assertDoesNotExist()

        rule.mainClock.autoAdvance = true
    }

    @Test
    fun aKeyTogglesAutoScroll() {
        val library = FakeLibraryRepository()
        setReaderContent(library, catalogWithPages(), autoScrollSpeedDpPerSec = 2000f)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Page 1 of 5").assertExists()

        // waitForIdle never idles while auto-scroll runs; drive time through mainClock only.
        rule.mainClock.autoAdvance = false
        rule.onRoot().performKeyInput { pressKey(Key.A) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithContentDescription("Page 1 of 5").assertDoesNotExist()

        rule.onRoot().performKeyInput { pressKey(Key.A) }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        val stopped = currentPageCounter()
        assertNotNull(stopped, "expected a page counter to still be showing once auto-scroll stopped")
        rule.waitForIdle()
        rule.waitForIdle()
        assertEquals(stopped, currentPageCounter(), "expected the position to stay put once auto-scroll stopped")
    }

    @Test
    fun pageDownStopsAutoScroll() {
        val library = FakeLibraryRepository()
        setReaderContent(library, catalogWithPages(), autoScrollSpeedDpPerSec = 2000f)
        rule.waitForIdle()

        rule.mainClock.autoAdvance = false
        rule.onRoot().performKeyInput { pressKey(Key.A) }
        rule.mainClock.advanceTimeBy(300)

        rule.onRoot().performKeyInput { pressKey(Key.PageDown) }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        val settled = currentPageCounter()
        assertNotNull(settled, "expected a page counter to be showing after PageDown settled")
        rule.waitForIdle()
        rule.waitForIdle()
        assertEquals(
            settled,
            currentPageCounter(),
            "expected no further scroll after auto-scroll was stopped by PageDown"
        )

        rule.onRoot().performKeyInput { pressKey(Key.PageUp) }
        rule.waitForIdle()
    }

    @Test
    fun pReanchorsAtTheTopOfThePreviousChapter() {
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

        assertTrue(descriptionVisible("Page 1 of 5"), "expected P to re-anchor at the top of ch-1, not a stale scroll offset")
    }

    @Test
    fun paletteVisibilityPausesAutoScroll() {
        val library = FakeLibraryRepository()
        val paletteVisibleState = mutableStateOf(false)
        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                ReaderScreen(
                    sourceId = SOURCE_ID,
                    mangaId = MANGA_ID,
                    chapterId = CHAPTER_ID,
                    chapters = listOf(Chapter(CHAPTER_ID, number = 1.0)),
                    catalog = catalogWithPages(),
                    downloads = FakeDownloadManager(),
                    library = library,
                    challengeSolver = FakeChallengeSolver(),
                    onBack = {},
                    onToggleFullscreen = {},
                    autoScrollSpeedDpPerSec = 2000f,
                    pageContent = { page -> FakePageContent(page) },
                    paletteVisible = paletteVisibleState.value,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Page 1 of 5").assertExists()

        // waitForIdle never idles while auto-scroll runs; drive time through mainClock only.
        rule.mainClock.autoAdvance = false
        rule.onRoot().performKeyInput { pressKey(Key.A) }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithContentDescription("Page 1 of 5").assertDoesNotExist()
        val moving = currentPageCounter()
        assertNotNull(moving, "expected a page counter to be showing while auto-scrolling")

        paletteVisibleState.value = true
        rule.mainClock.advanceTimeByFrame()
        val paused = currentPageCounter()
        assertNotNull(paused, "expected a page counter to still be showing once the palette opened")
        rule.mainClock.advanceTimeBy(500)
        assertEquals(paused, currentPageCounter(), "expected auto-scroll to pause while the palette is visible")

        paletteVisibleState.value = false
        rule.mainClock.advanceTimeBy(500)
        assertTrue(currentPageCounter() != paused, "expected auto-scroll to resume once the palette closed")

        rule.mainClock.autoAdvance = true
    }

    @Test
    fun paletteVisibilityPinsTheControlsOverlay() {
        val library = FakeLibraryRepository()
        val paletteVisibleState = mutableStateOf(false)
        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                ReaderScreen(
                    sourceId = SOURCE_ID,
                    mangaId = MANGA_ID,
                    chapterId = CHAPTER_ID,
                    chapters = listOf(Chapter(CHAPTER_ID, number = 1.0)),
                    catalog = catalogWithPages(),
                    downloads = FakeDownloadManager(),
                    library = library,
                    challengeSolver = FakeChallengeSolver(),
                    onBack = {},
                    onToggleFullscreen = {},
                    controlsAutoHideMillis = 1500,
                    pageContent = { page -> FakePageContent(page) },
                    paletteVisible = paletteVisibleState.value,
                )
            }
        }
        rule.waitForIdle()

        rule.mainClock.autoAdvance = false

        rule.onRoot().performMouseInput { click(Offset(400f, 400f)) }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("Ch. 1").assertExists()

        paletteVisibleState.value = true
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(10_000)
        rule.onNodeWithText("Ch. 1").assertExists()

        paletteVisibleState.value = false
        rule.mainClock.advanceTimeBy(10_000)
        rule.onNodeWithText("Ch. 1").assertDoesNotExist()

        rule.mainClock.autoAdvance = true
    }

    @Test
    fun prevButtonStopsAutoScrollAndReanchors() {
        val library = FakeLibraryRepository()
        setReaderContent(
            library,
            catalogWithPages(),
            chapterId = CHAPTER_2_ID,
            chapters = twoChapters,
            autoScrollSpeedDpPerSec = 2000f,
        )
        rule.waitForIdle()

        rule.mainClock.autoAdvance = false
        rule.onRoot().performKeyInput { pressKey(Key.A) }
        rule.mainClock.advanceTimeBy(400)

        rule.onNodeWithText("‹ Prev").performClick()
        // Reaching this point without hanging proves auto-scroll stopped.
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        assertTrue(descriptionVisible("Page 1 of 5"), "expected Prev to stop auto-scroll and re-anchor at ch-1's top")
    }

    @Test
    fun headerPolicyPinsUserAgentOntoPagesDeliveredToPageContent() {
        val library = FakeLibraryRepository()
        val policy = DefaultSourceHeaderPolicy(cookieStoreFor = { NoOpCookieStore() }, userAgentFor = { "Pinned/1.0" })
        var deliveredHeaders: Map<String, String>? = null
        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                ReaderScreen(
                    sourceId = SOURCE_ID,
                    mangaId = MANGA_ID,
                    chapterId = CHAPTER_ID,
                    chapters = listOf(Chapter(CHAPTER_ID, number = 1.0)),
                    catalog = catalogWithPages(),
                    downloads = FakeDownloadManager(),
                    library = library,
                    challengeSolver = FakeChallengeSolver(),
                    onBack = {},
                    onToggleFullscreen = {},
                    headerPolicy = policy,
                    pageContent = { page ->
                        if (page.index == 0) deliveredHeaders = page.headers
                        FakePageContent(page)
                    },
                )
            }
        }
        rule.waitForIdle()

        assertEquals("Pinned/1.0", deliveredHeaders?.get("User-Agent"))
    }

    @Test
    fun headerPolicyUrlRewriteReachesPageContent() {
        val library = FakeLibraryRepository()
        val policy = UrlRewritingHeaderPolicy(suffix = "?signed")
        var deliveredUrl: String? = null
        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                ReaderScreen(
                    sourceId = SOURCE_ID,
                    mangaId = MANGA_ID,
                    chapterId = CHAPTER_ID,
                    chapters = listOf(Chapter(CHAPTER_ID, number = 1.0)),
                    catalog = catalogWithPages(),
                    downloads = FakeDownloadManager(),
                    library = library,
                    challengeSolver = FakeChallengeSolver(),
                    onBack = {},
                    onToggleFullscreen = {},
                    headerPolicy = policy,
                    pageContent = { page ->
                        if (page.index == 0) deliveredUrl = page.url
                        FakePageContent(page)
                    },
                )
            }
        }
        rule.waitForIdle()

        assertEquals("${fakePages[0].url}?signed", deliveredUrl)
    }

    @OptIn(DelicateCoilApi::class)
    @Test
    fun failedPageImagesReserveHeightAndDoNotFalselyFinishTheChapter() {
        val library = FakeLibraryRepository()
        val engine = MockEngine { respond(ByteArray(0), HttpStatusCode.Forbidden) }
        SingletonImageLoader.reset()
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context).components { add(PolicyImageFetcher.Factory(HttpClient(engine))) }.build()
        }
        try {
            rule.setContent {
                ProvideMangoTheme(MangoDark) {
                    ReaderScreen(
                        sourceId = SOURCE_ID,
                        mangaId = MANGA_ID,
                        chapterId = CHAPTER_ID,
                        chapters = listOf(Chapter(CHAPTER_ID, number = 1.0)),
                        catalog = catalogWithPages(),
                        downloads = FakeDownloadManager(),
                        library = library,
                        challengeSolver = FakeChallengeSolver(),
                        onBack = {},
                        onToggleFullscreen = {},
                    )
                }
            }

            // Wall-clock wait: Coil resolves on its own dispatcher, not the virtual clock.
            rule.waitUntil(timeoutMillis = 10_000) {
                rule.onAllNodesWithTag("reader-page-error").fetchSemanticsNodes().isNotEmpty()
            }
            rule.waitForIdle()

            rule.onAllNodesWithTag("reader-page-error").onFirst().assertHeightIsAtLeast(100.dp)

            val progress = runBlocking { library.progress(SOURCE_ID, MANGA_ID, CHAPTER_ID) }
            assertTrue(
                progress == null || !progress.finished,
                "expected a screen of only-failed pages, never scrolled, to not be marked finished",
            )
        } finally {
            SingletonImageLoader.reset()
        }
    }
}
