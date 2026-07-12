package dev.mango.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.Download
import dev.mango.core.domain.DownloadStatus
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.MangaStatus
import dev.mango.core.domain.SourceInfo
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot plus end-to-end flows for the downloads screen and per-chapter enqueue, backed by
 * [FakeDownloadManager]/[FakeLibraryRepository]/[FakeCatalogRepository]. Style mirrors
 * ScreenScreenshotsTest and ScreenFlowTest.
 */
class DownloadsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun downloadsScreenshot() {
        val items = listOf(
            Download(
                sourceId = "FlameComics", mangaId = "manga-1", chapterId = "ch-1",
                mangaTitle = "Solo Leveling", chapterNumber = 1.0,
                status = DownloadStatus.QUEUED, pagesTotal = 0, pagesDone = 0,
            ),
            Download(
                sourceId = "FlameComics", mangaId = "manga-1", chapterId = "ch-2",
                mangaTitle = "Solo Leveling", chapterNumber = 2.0,
                status = DownloadStatus.RUNNING, pagesTotal = 10, pagesDone = 3,
            ),
            Download(
                sourceId = "FlameComics", mangaId = "manga-1", chapterId = "ch-3",
                mangaTitle = "Solo Leveling", chapterNumber = 3.0,
                status = DownloadStatus.DONE, pagesTotal = 12, pagesDone = 12,
            ),
            Download(
                sourceId = "FlameComics", mangaId = "manga-2", chapterId = "ch-4",
                mangaTitle = "Omniscient Reader", chapterNumber = 4.5,
                status = DownloadStatus.FAILED, pagesTotal = 8, pagesDone = 2,
            ),
        )
        val file = Screenshots.render("downloads") {
            ProvideMangoTheme(MangoDark) { DownloadsScreenContent(items = items) }
        }
        assertTrue(Files.size(file) > 0, "expected a non-empty PNG at $file")
    }

    @Test
    fun clickingDownloadOnAChapterRowEnqueuesAndDoesNotNavigate() {
        val library = FakeLibraryRepository()
        val downloads = FakeDownloadManager()
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapter = Chapter(chapterId = "ch-1", number = 1.0, title = "Chapter 1", publishedAt = null)
        val catalog = FakeCatalogRepository(
            sources = listOf(SourceInfo("FlameComics", "FlameComics")),
            results = mapOf("solo" to listOf(entry)),
            details = mapOf(("FlameComics" to "manga-1") to details),
            chapters = mapOf(("FlameComics" to "manga-1") to listOf(chapter)),
        )

        rule.setContent { TestAppShell(library, catalog, downloads) }

        rule.navigateVia("Browse")
        rule.onNodeWithText("Search…").performTextInput("solo")
        rule.waitForIdle()
        rule.onNodeWithText("solo").performImeAction()
        rule.waitForIdle()
        rule.onNodeWithText("Solo Leveling").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("↓").performClick()
        rule.waitForIdle()

        assertEquals(1, downloads.downloads.size)
        assertEquals("ch-1", downloads.downloads.single().chapterId)
        assertEquals(DownloadStatus.QUEUED, downloads.downloads.single().status)
        assertEquals("Solo Leveling", downloads.downloads.single().mangaTitle)
        assertEquals(1.0, downloads.downloads.single().chapterNumber)
        // Downloading a chapter implies the user cares about the series — it lands in the library.
        assertTrue(runBlocking { library.observeLibrary().first() }.any { it.entry.mangaId == "manga-1" })
        // Clicking the download affordance must not navigate to the reader — still on Details.
        rule.onNodeWithText("chapters", substring = true).assertExists()
    }

    @Test
    fun downloadsScreenRendersRowsFromTheFakesFlow() {
        val downloads = FakeDownloadManager(
            initial = listOf(
                Download(
                    sourceId = "FlameComics", mangaId = "manga-1", chapterId = "ch-1",
                    mangaTitle = "Solo Leveling", chapterNumber = 1.0,
                    status = DownloadStatus.DONE, pagesTotal = 5, pagesDone = 5,
                ),
                Download(
                    sourceId = "FlameComics", mangaId = "manga-1", chapterId = "ch-2",
                    mangaTitle = "Solo Leveling", chapterNumber = 2.0,
                    status = DownloadStatus.QUEUED, pagesTotal = 0, pagesDone = 0,
                ),
            ),
        )

        rule.setContent { ProvideMangoTheme(MangoDark) { DownloadsScreen(downloads) } }
        rule.waitForIdle()

        rule.onNodeWithText("Done").assertExists()
        rule.onNodeWithText("Queued").assertExists()
    }
}
