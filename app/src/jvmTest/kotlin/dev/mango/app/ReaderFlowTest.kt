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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
    override suspend fun chapters(sourceId: String, mangaId: String): List<Chapter> =
        delegate.chapters(sourceId, mangaId)

    override suspend fun pages(sourceId: String, mangaId: String, chapterId: String): List<Page> {
        if (chapterId == failingChapterId) error("scripted failure for $chapterId")
        return delegate.pages(sourceId, mangaId, chapterId)
    }

    override suspend fun setUserAgent(sourceId: String, userAgent: String) = delegate.setUserAgent(sourceId, userAgent)
    override suspend fun uninstall(sourceId: String) = delegate.uninstall(sourceId)
}

/**
 * Rewrites every page's url with a fixed suffix, headers untouched — proves [ReaderScreen]'s
 * pageContent hook observes a header policy's URL rewrite, not just its header merge (which
 * [headerPolicyPinsUserAgentOntoPagesDeliveredToPageContent] already covers).
 */
private class UrlRewritingHeaderPolicy(private val suffix: String) : SourceHeaderPolicy {
    override suspend fun headersFor(sourceId: String, url: String, headers: Map<String, String>): Map<String, String> =
        headers

    override suspend fun withPolicyHeaders(sourceId: String, pages: List<Page>): List<Page> =
        pages.map { it.copy(url = it.url + suffix) }
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
        // Matches ReaderScreen's own production default (CONTROLS_AUTO_HIDE_MS) — tests that
        // exercise the threshold/hide behavior override it explicitly.
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

    /** The currently visible "N / 5" page counter (chapter 1's page count), or null if none is. */
    private fun currentPageCounter(): String? = (1..5).map { "$it / 5" }.firstOrNull { textVisible(it) }

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
        // debounce window here. The fallback: the debounce is made injectable, and this test
        // passes 0 for a deterministic write instead of racing clocks.
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
        // null and the reader must fall back to the live catalog.
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

    // Finished tracks "read to the last page", not merely "opened" — the divider becoming
    // visible means ch-1's last PageRow has scrolled into (or past) view.
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
        // "/ 5" is ch-1's counter denominator (ch-2's is "/ 3") — proves ch-1 is still current.
        assertTrue(textVisible("/ 5"), "expected ch-1 to still be the current chapter after the failed append")
    }

    // The controls overlay reveals on genuine pointer movement anywhere in the reader (not just
    // a top-edge band — the old band-based reveal is gone) or a click, and ignores Compose
    // desktop's synthetic hover-move events fired at (near) the same position during a
    // stationary-cursor wheel scroll (filtered by ReaderOverlayState's 2px move-delta gate).
    // Updated from the old top-edge-band assertions to the new move-delta semantics (locked
    // decision: reveal on pointer movement anywhere, not a Y-position band).
    @Test
    fun controlsRevealOnGenuineMovementNotSubPixelJitterOrClick() {
        // HARNESS TRAP, same family as the auto-scroll one below: with autoAdvance=true, a
        // cursor parked at a fixed spot makes waitForIdle diverge — every completed hide
        // changes the hit-tree under the stationary cursor, Compose synthesizes a hover Move,
        // and (were it not for the move-delta gate) the show/hide cycle could spin forever. So:
        // autoAdvance=false, explicit advanceTimeBy only. The hide window is deliberately huge
        // (10s) so reveal-asserts can advance generously (event delivery + recomposition)
        // without racing the hide.
        val library = FakeLibraryRepository()
        setReaderContent(library, catalogWithPages(), controlsAutoHideMillis = 10_000)
        rule.waitForIdle()

        // Initial controls start visible; let the auto-hide fire before any pointer exists.
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(11_000)
        rule.onNodeWithText("Ch. 1").assertDoesNotExist()

        // Establish a known pointer baseline (this move may or may not itself reveal, depending
        // on wherever the test harness's cursor implicitly started), then let any resulting
        // reveal expire so the assertions below start from a clean, hidden state.
        rule.onRoot().performMouseInput { moveTo(Offset(400f, 400f)) }
        rule.mainClock.advanceTimeBy(11_000)
        rule.onNodeWithText("Ch. 1").assertDoesNotExist()

        // A sub-2px move from that baseline must NOT reveal.
        rule.onRoot().performMouseInput { moveTo(Offset(401f, 400f)) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Ch. 1").assertDoesNotExist()

        // A move past the threshold reveals — anywhere in the reader, not just near the top.
        rule.onRoot().performMouseInput { moveTo(Offset(401f, 450f)) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Ch. 1").assertExists()

        // Park the cursor, then let the pending hide fire.
        rule.mainClock.advanceTimeBy(11_000)
        rule.onNodeWithText("Ch. 1").assertDoesNotExist()

        // A click anywhere reveals.
        rule.onRoot().performMouseInput { click(Offset(400f, 400f)) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Ch. 1").assertExists()

        // A second click on the page area (not an overlay control) dismisses without waiting
        // out the idle timer.
        rule.onRoot().performMouseInput { click(Offset(400f, 400f)) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Ch. 1").assertDoesNotExist()

        rule.mainClock.autoAdvance = true
    }

    // The A key toggles auto-scroll on and off.
    @Test
    fun aKeyTogglesAutoScroll() {
        val library = FakeLibraryRepository()
        setReaderContent(library, catalogWithPages(), autoScrollSpeedDpPerSec = 2000f)
        rule.waitForIdle()
        rule.onNodeWithText("1 / 5").assertExists()

        // CRITICAL: while autoScrolling's withFrameNanos loop runs, waitForIdle() never goes
        // idle — the loop is always pending a frame. Drive time only through the main clock.
        rule.mainClock.autoAdvance = false
        rule.onRoot().performKeyInput { pressKey(Key.A) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("1 / 5").assertDoesNotExist()

        rule.onRoot().performKeyInput { pressKey(Key.A) } // stop
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        val stopped = currentPageCounter()
        assertNotNull(stopped, "expected a page counter to still be showing once auto-scroll stopped")
        rule.waitForIdle()
        rule.waitForIdle()
        assertEquals(stopped, currentPageCounter(), "expected the position to stay put once auto-scroll stopped")
    }

    // A paging key interrupts auto-scroll.
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

        // PageDown's animateScrollBy has settled and auto-scroll is stopped: the position must
        // not keep drifting.
        val settled = currentPageCounter()
        assertNotNull(settled, "expected a page counter to be showing after PageDown settled")
        rule.waitForIdle()
        rule.waitForIdle()
        assertEquals(
            settled,
            currentPageCounter(),
            "expected no further scroll after auto-scroll was stopped by PageDown"
        )

        // and the reader must still respond normally to further input, with no hang.
        rule.onRoot().performKeyInput { pressKey(Key.PageUp) }
        rule.waitForIdle()
    }

    // A fresh LazyListState per anchor means P's re-anchor never renders one frame of the OLD
    // chapter's scroll offset before the restore effect repositions it.
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

        assertTrue(textVisible("1 / 5"), "expected P to re-anchor at the top of ch-1, not a stale scroll offset")
    }

    // While the palette overlay is up the reader has no keyboard (A can't stop the strip), so
    // the auto-scroll drive loop pauses and auto-resumes on close.
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
        rule.onNodeWithText("1 / 5").assertExists()

        // CRITICAL: while autoScrolling's withFrameNanos loop runs, waitForIdle() never goes
        // idle — the loop is always pending a frame. Drive time only through the main clock.
        rule.mainClock.autoAdvance = false
        rule.onRoot().performKeyInput { pressKey(Key.A) }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("1 / 5").assertDoesNotExist()
        val moving = currentPageCounter()
        assertNotNull(moving, "expected a page counter to be showing while auto-scrolling")

        // Open the palette. Advance one frame first so the LaunchedEffect(..., paletteVisible)
        // restart is observed before we snapshot the "paused" baseline — otherwise the
        // recomposition's own frame could be mistaken for drift.
        paletteVisibleState.value = true
        rule.mainClock.advanceTimeByFrame()
        val paused = currentPageCounter()
        assertNotNull(paused, "expected a page counter to still be showing once the palette opened")
        rule.mainClock.advanceTimeBy(500)
        assertEquals(paused, currentPageCounter(), "expected auto-scroll to pause while the palette is visible")

        // Close the palette: the loop must auto-resume with no further key press.
        paletteVisibleState.value = false
        rule.mainClock.advanceTimeBy(500)
        assertTrue(currentPageCounter() != paused, "expected auto-scroll to resume once the palette closed")

        rule.mainClock.autoAdvance = true
    }

    // While the palette overlay is up the controls overlay is pinned visible: no idle hide is
    // ever scheduled (the reveal effect early-returns on paletteVisible); closing the palette
    // resumes the idle countdown. This fails if that early-return is removed.
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

        // Same clock discipline as the reveal test above: autoAdvance=false, explicit
        // advanceTimeBy only, so the idle hide fires exactly when this test says so.
        rule.mainClock.autoAdvance = false

        // Reveal via a click, then confirm the controls are up.
        rule.onRoot().performMouseInput { click(Offset(400f, 400f)) }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("Ch. 1").assertExists()

        // Open the palette, then advance well past the idle window: still pinned visible.
        paletteVisibleState.value = true
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(10_000)
        rule.onNodeWithText("Ch. 1").assertExists()

        // Close the palette: the idle countdown resumes and the controls hide.
        paletteVisibleState.value = false
        rule.mainClock.advanceTimeBy(10_000)
        rule.onNodeWithText("Ch. 1").assertDoesNotExist()

        rule.mainClock.autoAdvance = true
    }

    // The overlay Prev button must stop auto-scroll before re-anchoring — a drive loop left
    // running would keep scrolling the OLD detached listState after the re-anchor mints a
    // fresh one (visible freeze).
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

        // Controls are still up (auto-hide is 2000ms and only 400ms has elapsed).
        rule.onNodeWithText("‹ Prev").performClick()
        // If the drive loop survived the click still bound to the old listState, waitForIdle
        // would hang on its always-pending frame — reaching the assertion below is itself the
        // proof the button stopped auto-scroll.
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        assertTrue(textVisible("1 / 5"), "expected Prev to stop auto-scroll and re-anchor at ch-1's top")
    }

    // Image fetches bypass ApplicationHost, so ReaderScreen is the one place left to apply the
    // per-source header policy to pages before they reach a renderer.
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

    // Prepared page urls (interceptor-rewritten, e.g. a signed CDN url) must reach the renderer,
    // not just prepared headers.
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

    // Regression: without an error slot, a failed Coil load rendered zero height, which the
    // completion heuristic below (last PageRow scrolled into/past view) read as "already at the
    // bottom" — a wall of failed pages instantly (falsely) marked the chapter finished with the
    // reader never actually scrolled. This composes the real DefaultReaderPage path (no
    // pageContent override) against a PolicyImageFetcher-equipped loader that 403s every page.
    // setSafe is first-wins per JVM: reset before installing so an earlier test's loader (if one
    // already won the singleton) can't silently stay installed and leave this test exercising the
    // wrong Coil pipeline (no 403s, no error slot, no wall-clock wait to satisfy) — and reset
    // again on the way out so the 403 loader installed here never leaks to a later test.
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

            // Real (wall-clock) wait: the failed fetch resolves on Coil's own dispatcher, not the
            // Compose test's virtual clock.
            rule.waitUntil(timeoutMillis = 10_000) {
                rule.onAllNodesWithTag("reader-page-error").fetchSemanticsNodes().isNotEmpty()
            }
            rule.waitForIdle()

            // The error slot reserves the 3:4 aspect-ratio fallback height (DefaultReaderPage) at
            // the strip's full width — a real minimum, not the placeholder 1.dp that would also
            // pass for a near-invisible sliver.
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
