package dev.mango.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.LibraryItem
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.MangaStatus
import dev.mango.core.domain.SourceInfo
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

private const val FRAME_MS = 16L
private const val SETTLE_FRAMES = 30

@OptIn(ExperimentalTestApi::class)
class ScreenTransitionTest {
    private val entries = List(30) { i -> MangaEntry(sourceId = "FlameComics", mangaId = "manga-$i", title = "Series $i") }

    private fun library() = FakeLibraryRepository(entries.map { LibraryItem(it, Clock.System.now()) })

    private fun catalog() = FakeCatalogRepository(
        sources = listOf(SourceInfo("FlameComics", "FlameComics")),
        details = entries.associate { (it.sourceId to it.mangaId) to MangaDetails(entry = it, status = MangaStatus.ONGOING) },
        chapters = mapOf(("FlameComics" to "manga-0") to listOf(Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null))),
    )

    /** Advances the clock frame by frame for [sampleMs], recording [tag]'s left edge each frame. */
    private fun ComposeUiTest.leftEdgesOf(tag: String, sampleMs: Long = 300): List<Float> = List((sampleMs / FRAME_MS).toInt()) {
        mainClock.advanceTimeBy(FRAME_MS)
        onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.left
    }.filterNotNull()

    private fun ComposeUiTest.openDetailsFromLibrary(motion: MangoMotion, sampleMs: Long = 300): List<Float> {
        mainClock.autoAdvance = false
        setContent { TestAppShell(library(), catalog(), FakeDownloadManager(), motion = motion) }
        repeat(SETTLE_FRAMES) { mainClock.advanceTimeByFrame() }
        onNodeWithText("Series 0").performClick()
        return leftEdgesOf("screen:Details", sampleMs)
    }

    @Test
    fun pushSlidesTheIncomingScreen() = runComposeUiTest {
        val edges = openDetailsFromLibrary(MangoMotion())
        assertTrue(edges.distinct().size > 1, "$edges")
    }

    @Test
    fun reducedMotionFadesWithoutSliding() = runComposeUiTest {
        val fadeMs = 120 + 3 * FRAME_MS
        val early = openDetailsFromLibrary(MangoMotion(reduced = true), sampleMs = fadeMs)
        assertTrue(onAllNodesWithTag("screen:Library").fetchSemanticsNodes().isEmpty())
        val edges = early + leftEdgesOf("screen:Details", sampleMs = 300 - fadeMs)
        assertTrue(edges.isNotEmpty(), "$edges")
        assertEquals(1, edges.distinct().size, "$edges")
    }

    @Test
    fun readerEntryFadesWithoutSliding() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { TestAppShell(library(), catalog(), FakeDownloadManager()) }
        repeat(SETTLE_FRAMES) { mainClock.advanceTimeByFrame() }
        onNodeWithText("Series 0").performClick()
        repeat(SETTLE_FRAMES) { mainClock.advanceTimeByFrame() }
        onNodeWithText("Start reading").performClick()
        val edges = leftEdgesOf("screen:Reader")
        assertTrue(edges.isNotEmpty(), "$edges")
        assertEquals(1, edges.distinct().size, "$edges")
    }

    @Test
    fun replaceCurrentDoesNotAnimate() = runComposeUiTest {
        val navigator = Navigator()
        mainClock.autoAdvance = false
        setContent { TestAppShell(library(), catalog(), FakeDownloadManager(), navigator = navigator) }
        repeat(SETTLE_FRAMES) { mainClock.advanceTimeByFrame() }
        onNodeWithText("Series 1").performClick()
        repeat(SETTLE_FRAMES) { mainClock.advanceTimeByFrame() }
        val settledLeft = onNodeWithTag("screen:Details").fetchSemanticsNode().boundsInRoot.left

        // Series 1 has no chapters, so the toggled flag has nothing to continue into.
        val details = navigator.current.screen as Screen.Details
        navigator.replaceCurrent(details.copy(autoContinue = !details.autoContinue))
        repeat(6) {
            mainClock.advanceTimeByFrame()
            val nodes = onAllNodesWithTag("screen:Details").fetchSemanticsNodes()
            assertEquals(1, nodes.size, "frame $it")
            assertEquals(settledLeft, nodes.single().boundsInRoot.left, "frame $it")
        }
    }

    @Test
    fun topLevelSwitchDoesNotSlideSideways() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { TestAppShell(library(), catalog(), FakeDownloadManager()) }
        repeat(SETTLE_FRAMES) { mainClock.advanceTimeByFrame() }
        onNodeWithTag(SIDEBAR_TOGGLE_TAG).performClick()
        repeat(SETTLE_FRAMES) { mainClock.advanceTimeByFrame() }
        onNodeWithText("Settings").performClick()
        val edges = leftEdgesOf("screen:Settings")
        assertTrue(edges.isNotEmpty(), "$edges")
        assertEquals(1, edges.distinct().size, "$edges")
    }

    @Test
    fun scrollIsKeptWhenReturningFromDetails() = runComposeUiTest {
        setContent { TestAppShell(library(), catalog(), FakeDownloadManager()) }
        waitForIdle()
        onNodeWithTag(LIBRARY_GRID_TEST_TAG).performScrollToIndex(20)
        waitForIdle()
        onNodeWithText("Series 20").performClick()
        waitForIdle()
        onNodeWithTag("screen:Details").assertExists()

        onNodeWithContentDescription("Back").performClick()
        waitForIdle()

        onNodeWithText("Series 20").assertIsDisplayed()
    }

    /** Unclipped, unlike boundsInRoot: a cover in flight pokes outside its column. */
    private fun ComposeUiTest.boundsOf(tag: String): Rect {
        val node = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        return Rect(node.positionInRoot, node.size.toSize())
    }

    /**
     * Opens Series 0's Details from the Library grid. Returns the tile's bounds, then the Details
     * cover's on the first frame it exists (it waits on the details load) and once settled.
     */
    private fun ComposeUiTest.openSeries0Cover(motion: MangoMotion): Triple<Rect, Rect, Rect> {
        mainClock.autoAdvance = false
        setContent { TestAppShell(library(), catalog(), FakeDownloadManager(), motion = motion) }
        repeat(SETTLE_FRAMES) { mainClock.advanceTimeByFrame() }
        val tile = boundsOf("cover:FlameComics/manga-0")
        onNodeWithText("Series 0").performClick()
        var frames = 0
        while (onAllNodesWithTag("detailsCover", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            assertTrue(frames++ < 3, "the Details cover never appeared")
            mainClock.advanceTimeByFrame()
        }
        val firstFrame = boundsOf("detailsCover")
        repeat(SETTLE_FRAMES) { mainClock.advanceTimeByFrame() }
        val settled = boundsOf("detailsCover")
        repeat(SETTLE_FRAMES) { mainClock.advanceTimeByFrame() }
        assertEquals(settled, boundsOf("detailsCover"), "the cover is still moving")
        return Triple(tile, firstFrame, settled)
    }

    @Test
    fun coverGrowsFromTheLibraryTileIntoDetails() = runComposeUiTest {
        val (tile, firstFrame, settled) = openSeries0Cover(MangoMotion())
        val tolerance = with(density) { 4.dp.toPx() }
        val edges = listOf(
            tile.left - firstFrame.left,
            tile.top - firstFrame.top,
            tile.right - firstFrame.right,
            tile.bottom - firstFrame.bottom,
        )
        assertTrue(edges.all { abs(it) <= tolerance }, "tile $tile, first frame $firstFrame")
        assertNotEquals(tile, settled)
    }

    @Test
    fun reducedMotionHasNoSharedCover() = runComposeUiTest {
        val (_, firstFrame, settled) = openSeries0Cover(MangoMotion(reduced = true))
        assertEquals(settled, firstFrame)
    }
}
