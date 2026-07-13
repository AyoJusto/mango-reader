package dev.mango.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.MangaStatus
import dev.mango.core.domain.ReadProgress
import dev.mango.core.domain.SourceInfo
import kotlin.test.assertEquals
import kotlin.time.Clock
import org.junit.Rule
import org.junit.Test

/**
 * End-to-end navigation and interaction flows through [AppShell] and [DetailsScreen], backed
 * by [FakeLibraryRepository]/[FakeCatalogRepository]. CMP 1.11 uses StandardTestDispatcher —
 * every action that launches a coroutine is followed by [waitForIdle] before the next assert.
 */
class ScreenFlowTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun appShellStartsOnEmptyLibraryThenSidebarSwitchesToBrowse() {
        val library = FakeLibraryRepository()
        val catalog = FakeCatalogRepository(sources = listOf(SourceInfo("FlameComics", "FlameComics")))

        rule.setContent { TestAppShell(library, catalog, FakeDownloadManager()) }

        rule.onNodeWithText("Nothing here yet").assertExists()

        rule.navigateVia("Browse")

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

        rule.setContent { TestAppShell(library, catalog, FakeDownloadManager()) }

        rule.navigateVia("Browse")

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

        rule.setContent { TestAppShell(library, catalog, FakeDownloadManager()) }

        rule.navigateVia("Browse")

        rule.onNodeWithText("Search…").performTextInput("solo")
        rule.waitForIdle()
        rule.onNodeWithText("solo").performImeAction()
        rule.waitForIdle()

        rule.onNodeWithText("Solo Leveling").assertExists()

        rule.navigateVia("Library")
        rule.navigateVia("Browse")

        rule.onNodeWithText("solo").assertExists()
        rule.onNodeWithText("Solo Leveling").assertExists()
        assertEquals(1, catalog.searchCallCount)
    }

    // The Continue button's three variants — no progress, in-progress, and finished.
    @Test
    fun startReadingButtonOpensTheFirstChapterWhenThereIsNoProgressYet() {
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapters = listOf(
            Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null),
            Chapter(chapterId = "c2", number = 2.0, title = null, publishedAt = null),
        )
        var opened: Chapter? = null

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreenContent(
                    details = details,
                    chapters = chapters,
                    inLibrary = false,
                    onOpenChapter = { chapter, _ -> opened = chapter },
                    onDownloadChapter = { _, _ -> },
                    onDownloadAll = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Start reading").performClick()
        rule.waitForIdle()

        assertEquals("c1", opened?.chapterId)
    }

    @Test
    fun continueButtonShowsTheSavedPageAndReopensTheUnfinishedChapter() {
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapters = listOf(
            Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null),
            Chapter(chapterId = "c2", number = 2.0, title = null, publishedAt = null),
        )
        var opened: Chapter? = null

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreenContent(
                    details = details,
                    chapters = chapters,
                    inLibrary = false,
                    latestProgress = ReadProgress(chapterId = "c1", page = 2, updatedAt = Clock.System.now(), finished = false),
                    onOpenChapter = { chapter, _ -> opened = chapter },
                    onDownloadChapter = { _, _ -> },
                    onDownloadAll = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Continue — Ch. 1 · p. 3").performClick()
        rule.waitForIdle()

        assertEquals("c1", opened?.chapterId)
    }

    @Test
    fun continueButtonAdvancesToTheNextChapterOnceTheLatestOneIsFinished() {
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapters = listOf(
            Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null),
            Chapter(chapterId = "c2", number = 2.0, title = null, publishedAt = null),
        )
        var opened: Chapter? = null

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreenContent(
                    details = details,
                    chapters = chapters,
                    inLibrary = false,
                    latestProgress = ReadProgress(chapterId = "c1", page = 4, updatedAt = Clock.System.now(), finished = true),
                    onOpenChapter = { chapter, _ -> opened = chapter },
                    onDownloadChapter = { _, _ -> },
                    onDownloadAll = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Continue — Ch. 2").performClick()
        rule.waitForIdle()

        assertEquals("c2", opened?.chapterId)
    }
}
