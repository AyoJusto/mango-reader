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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import dev.mango.core.domain.Page
import dev.mango.core.domain.SourceInfo
import kotlinx.coroutines.runBlocking
import kotlin.test.assertNotNull
import org.junit.Rule
import org.junit.Test

private const val SOURCE_ID = "FlameComics"
private const val MANGA_ID = "manga-1"
private const val CHAPTER_ID = "ch-1"

/**
 * End-to-end input and progress-persistence flows through [ReaderScreen], backed by
 * [FakeLibraryRepository]/[FakeCatalogRepository]. Page content is a colored placeholder box
 * (not Coil) so these run offscreen with no network. CMP 1.11 uses StandardTestDispatcher —
 * every action that launches a coroutine is followed by [waitForIdle] before the next assert.
 */
class ReaderFlowTest {
    @get:Rule
    val rule = createComposeRule()

    private val fakePages = (0 until 5).map { index -> Page(index = index, url = "https://example.test/$index.jpg") }

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
        pages = mapOf(Triple(SOURCE_ID, MANGA_ID, CHAPTER_ID) to fakePages),
    )

    private fun setReaderContent(
        library: FakeLibraryRepository,
        catalog: FakeCatalogRepository,
        progressDebounceMillis: Long = 500,
    ) {
        rule.setContent {
            MangoTheme {
                ReaderScreen(
                    sourceId = SOURCE_ID,
                    mangaId = MANGA_ID,
                    chapterId = CHAPTER_ID,
                    catalog = catalog,
                    library = library,
                    onBack = {},
                    onToggleFullscreen = {},
                    progressDebounceMillis = progressDebounceMillis,
                    pageContent = { page -> FakePageContent(page) },
                )
            }
        }
    }

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
}
