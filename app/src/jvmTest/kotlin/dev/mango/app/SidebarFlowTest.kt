package dev.mango.app

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.Download
import dev.mango.core.domain.DownloadStatus
import dev.mango.core.domain.LibraryItem
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.MangaStatus
import dev.mango.core.domain.SourceInfo
import java.awt.Frame
import java.nio.file.Files
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * End-to-end sidebar flows through [AppShell] (via [TestAppShell]'s hoisted-state wiring),
 * backed by the shared fakes. Lookups for text that also renders on the screen behind the
 * overlay are scoped to [SIDEBAR_TEST_TAG]'s subtree, same idiom as [PaletteFlowTest].
 */
class SidebarFlowTest {
    @get:Rule
    val rule = createComposeRule()

    private val inSidebar = hasAnyAncestor(hasTestTag(SIDEBAR_TEST_TAG))

    @Test
    fun titleBarGlyphTogglesTheSidebarOpenAndClosed() {
        rule.setContent { TestAppShell(FakeLibraryRepository(), FakeCatalogRepository(), FakeDownloadManager()) }
        rule.waitForIdle()

        rule.onNodeWithTag(SIDEBAR_TEST_TAG).assertDoesNotExist()

        rule.onNodeWithTag(SIDEBAR_TOGGLE_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(SIDEBAR_TEST_TAG).assertExists()

        rule.onNodeWithTag(SIDEBAR_TOGGLE_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(SIDEBAR_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun navClickNavigatesToTheScreenAndClosesTheSidebar() {
        rule.setContent { TestAppShell(FakeLibraryRepository(), FakeCatalogRepository(), FakeDownloadManager()) }
        rule.waitForIdle()

        rule.onNodeWithTag(SIDEBAR_TOGGLE_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Browse").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("No sources installed — add one from the Extensions tab").assertExists()
        rule.onNodeWithTag(SIDEBAR_TEST_TAG).assertDoesNotExist()
    }

    // The sidebar's Continue card opens Details with the one-shot auto-continue, which fires
    // the existing Continue action into the Reader. Pages come from local downloads so the
    // reader renders offline with no network.
    @Test
    fun continueCardResumesStraightIntoTheReader() {
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val library = FakeLibraryRepository(listOf(LibraryItem(entry, Clock.System.now())))
        runBlocking { library.setProgress("FlameComics", "manga-1", "c1", page = 1, finished = false) }
        val chapters = listOf(
            Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null),
            Chapter(chapterId = "c2", number = 2.0, title = null, publishedAt = null),
        )
        val catalog = FakeCatalogRepository(
            sources = listOf(SourceInfo("FlameComics", "FlameComics")),
            details = mapOf(("FlameComics" to "manga-1") to MangaDetails(entry = entry, status = MangaStatus.ONGOING)),
            chapters = mapOf(("FlameComics" to "manga-1") to chapters),
        )
        val dir = Files.createTempDirectory("sidebar-continue-test")
        val file0 = dir.resolve("0000.jpg").also { Files.write(it, byteArrayOf(1)) }
        val file1 = dir.resolve("0001.jpg").also { Files.write(it, byteArrayOf(2)) }
        val downloads = FakeDownloadManager(
            localPagesByKey = mapOf("FlameComics/manga-1/c1" to listOf(file0.toString(), file1.toString())),
        )

        rule.setContent { TestAppShell(library, catalog, downloads) }
        rule.waitForIdle()

        rule.onNodeWithTag(SIDEBAR_TOGGLE_TAG).performClick()
        rule.waitForIdle()

        rule.onNode(hasText("CONTINUE READING") and inSidebar).assertExists()
        rule.onNode(hasText("Solo Leveling") and inSidebar).performClick()
        rule.waitForIdle()
        rule.waitForIdle()

        // The "offline" page counter is Reader-only text: its presence proves the Continue
        // card ended in the Reader, serving the downloaded pages.
        rule.onNode(hasText("· offline", substring = true)).assertExists()
        rule.onNodeWithTag(SIDEBAR_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun downloadsNavItemShowsACountPillWhileDownloadsArePending() {
        val downloads = FakeDownloadManager(
            initial = listOf(
                Download(
                    sourceId = "FlameComics", mangaId = "manga-1", chapterId = "ch-1",
                    mangaTitle = "Solo Leveling", chapterNumber = 1.0,
                    status = DownloadStatus.QUEUED, pagesTotal = 0, pagesDone = 0,
                ),
                Download(
                    sourceId = "FlameComics", mangaId = "manga-1", chapterId = "ch-2",
                    mangaTitle = "Solo Leveling", chapterNumber = 2.0,
                    status = DownloadStatus.DONE, pagesTotal = 5, pagesDone = 5,
                ),
            ),
        )

        rule.setContent { TestAppShell(FakeLibraryRepository(), FakeCatalogRepository(), downloads) }
        rule.waitForIdle()

        rule.onNodeWithTag(SIDEBAR_TOGGLE_TAG).performClick()
        rule.waitForIdle()

        // Only the QUEUED row counts; the DONE row must not inflate the pill.
        rule.onNode(hasText("1") and inSidebar).assertExists()
    }

    // Continue cards show the chapter number once it's known (set alongside the page on every
    // Reader write), falling back to the saved page only for progress written before that
    // column existed (chapterNumber defaults to 0).
    @Test
    fun continueCardShowsTheChapterNumberWhenKnownAndFallsBackToTheSavedPageOtherwise() {
        val withChapter = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val withoutChapter = MangaEntry(sourceId = "FlameComics", mangaId = "manga-2", title = "Omniscient Reader")
        val library = FakeLibraryRepository(
            listOf(
                LibraryItem(withChapter, Clock.System.now()),
                LibraryItem(withoutChapter, Clock.System.now()),
            ),
        )
        runBlocking {
            library.setProgress("FlameComics", "manga-1", "c1", page = 4, chapterNumber = 12.0)
            library.setProgress("FlameComics", "manga-2", "c1", page = 6)
        }

        rule.setContent { TestAppShell(library, FakeCatalogRepository(), FakeDownloadManager()) }
        rule.waitForIdle()

        rule.onNodeWithTag(SIDEBAR_TOGGLE_TAG).performClick()
        rule.waitForIdle()

        rule.onNode(hasText("Ch. 12") and inSidebar).assertExists()
        rule.onNode(hasText("p. 7") and inSidebar).assertExists()
    }

    // Tests run on a stock JDK: the JBR reflection path must degrade to null (OS-decorated
    // fallback), never throw.
    @Test
    fun applyJbrTitleBarOnAStockJdkReturnsNullAndDoesNotThrow() {
        val frame = Frame()
        try {
            assertNull(applyJbrTitleBar(frame, 44f))
        } finally {
            frame.dispose()
        }
    }
}
