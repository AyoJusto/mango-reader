package dev.mango.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.mango.core.domain.Page
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Screenshots for the reader: rendered at 1280x800 via the pure [ReaderContent], never
 * asserted byte-exact. Page content is a colored placeholder box (not Coil) so these run
 * offscreen with no network.
 */
class ReaderScreenshotsTest {
    // Muted, distinct-enough-to-eyeball colors — not the real dragon palette, just visual markers.
    private val pageColors = listOf(Color(0xFF3A4A5A), Color(0xFF5A4A3A), Color(0xFF4A5A3A))
    private val fakePages = (0 until 5).map { index -> Page(index = index, url = "https://example.test/$index.jpg") }

    @Composable
    private fun FakePageContent(page: Page) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(500.dp)
                .background(pageColors[page.index % pageColors.size]),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "${page.index + 1}", style = MaterialTheme.typography.labelLarge)
        }
    }

    private val fakeSegments = listOf(
        ReaderSegment(chapterId = "ch-12", shortLabel = "Ch. 12", label = "Chapter 12", pages = fakePages),
    )

    @Test
    fun controlsVisible() {
        val file = Screenshots.render("reader-controls-visible") {
            MangoTheme {
                ReaderContent(
                    segments = fakeSegments,
                    listState = rememberLazyListState(),
                    controlsVisible = true,
                    onBack = {},
                    pageContent = { page, _ -> FakePageContent(page) },
                )
            }
        }
        assertTrue(Files.size(file) > 0, "expected a non-empty PNG at $file")
    }

    @Test
    fun immersive() {
        val file = Screenshots.render("reader-immersive") {
            MangoTheme {
                ReaderContent(
                    segments = fakeSegments,
                    listState = rememberLazyListState(),
                    controlsVisible = false,
                    onBack = {},
                    pageContent = { page, _ -> FakePageContent(page) },
                )
            }
        }
        assertTrue(Files.size(file) > 0, "expected a non-empty PNG at $file")
    }
}
