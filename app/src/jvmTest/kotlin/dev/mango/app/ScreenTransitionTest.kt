package dev.mango.app

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.mango.core.domain.LibraryItem
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.MangaStatus
import dev.mango.core.domain.SourceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
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

        onNodeWithText("←").performClick()
        waitForIdle()

        onNodeWithText("Series 20").assertIsDisplayed()
    }
}
