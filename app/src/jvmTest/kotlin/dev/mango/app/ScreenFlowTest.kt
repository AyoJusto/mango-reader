package dev.mango.app

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.LibraryItem
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.MangaStatus
import dev.mango.core.domain.ReadProgress
import dev.mango.core.domain.SourceInfo
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
                    latestProgress = ReadProgress(
                        chapterId = "c1",
                        page = 2,
                        updatedAt = Clock.System.now(),
                        finished = false
                    ),
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
                    latestProgress = ReadProgress(
                        chapterId = "c1",
                        page = 4,
                        updatedAt = Clock.System.now(),
                        finished = true
                    ),
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

    private val soloEntry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")

    private fun soloCatalog(chapters: List<Chapter> = emptyList()) = FakeCatalogRepository(
        sources = listOf(SourceInfo("FlameComics", "FlameComics")),
        results = mapOf("solo" to listOf(soloEntry)),
        details = mapOf(("FlameComics" to "manga-1") to MangaDetails(entry = soloEntry, status = MangaStatus.ONGOING)),
        chapters = mapOf(("FlameComics" to "manga-1") to chapters),
    )

    @Test
    fun titleBarBackIsDisabledOnLibrary() {
        rule.setContent { TestAppShell(FakeLibraryRepository(), soloCatalog(), FakeDownloadManager()) }

        rule.onNodeWithContentDescription("Back").assertExists().assertIsNotEnabled()
    }

    @Test
    fun titleBarBackReturnsFromDetails() {
        val library = FakeLibraryRepository(listOf(LibraryItem(soloEntry, Clock.System.now())))
        rule.setContent { TestAppShell(library, soloCatalog(), FakeDownloadManager()) }

        rule.onNodeWithText("Solo Leveling").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("screen:Details").assertExists()
        rule.onNodeWithContentDescription("Back").assertIsEnabled().performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("screen:Library").assertExists()
        rule.onNodeWithTag("screen:Details").assertDoesNotExist()
    }

    @Test
    fun detailsHasNoInScreenBackArrow() {
        val library = FakeLibraryRepository(listOf(LibraryItem(soloEntry, Clock.System.now())))
        rule.setContent { TestAppShell(library, soloCatalog(), FakeDownloadManager()) }

        rule.onNodeWithText("Solo Leveling").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("screen:Details").assertExists()
        rule.onNodeWithText("←").assertDoesNotExist()
    }

    @Test
    fun backFromSearchOpenedDetailsReturnsToSearch() {
        rule.setContent { TestAppShell(FakeLibraryRepository(), soloCatalog(), FakeDownloadManager()) }

        rule.navigateVia("Search")
        rule.onNodeWithText("Search all sources…").performTextInput("solo")
        rule.waitForIdle()
        rule.onNodeWithText("solo").performImeAction()
        rule.waitForIdle()
        rule.onNodeWithText("Solo Leveling").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Add to library").assertExists()

        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("solo").assertExists()
        rule.onNodeWithText("Add to library").assertDoesNotExist()
    }

    // The sidebar's Continue card opens Details with a one-shot auto-continue; backing out of
    // the Reader must land on Details without that flag, or Details would re-fire straight
    // back into the Reader.
    @Test
    fun continueThenBackFromReaderStaysOnDetails() {
        val library = FakeLibraryRepository(listOf(LibraryItem(soloEntry, Clock.System.now())))
        runBlocking { library.setProgress("FlameComics", "manga-1", "c1", page = 1, finished = false) }
        val catalog = soloCatalog(
            chapters = listOf(
                Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null),
                Chapter(chapterId = "c2", number = 2.0, title = null, publishedAt = null),
            ),
        )
        val dir = Files.createTempDirectory("continue-back-test")
        val file0 = dir.resolve("0000.jpg").also { Files.write(it, byteArrayOf(1)) }
        val downloads = FakeDownloadManager(
            localPagesByKey = mapOf("FlameComics/manga-1/c1" to listOf(file0.toString())),
        )
        val navigator = Navigator()

        rule.setContent { TestAppShell(library, catalog, downloads, navigator = navigator) }
        rule.waitForIdle()
        rule.onNodeWithTag(SIDEBAR_TOGGLE_TAG).performClick()
        rule.waitForIdle()
        rule.onNode(hasText("Solo Leveling") and hasAnyAncestor(hasTestTag(SIDEBAR_TEST_TAG))).performClick()
        rule.waitForIdle()
        rule.waitForIdle()
        // "offline" is Reader-only text: the bar's label for downloaded pages.
        rule.onNode(hasText("offline")).assertExists()

        rule.runOnIdle { navigator.back() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()

        rule.onAllNodesWithText("Solo Leveling").onFirst().assertExists()
        rule.onNode(hasText("offline")).assertDoesNotExist()
    }

    // Nucleus reports a side-button press as a primary click, so the shell must consume it
    // before any clickable underneath sees it.
    @Test
    fun mouseBackButtonGoesBackAndDoesNotClick() {
        val library = FakeLibraryRepository(listOf(LibraryItem(soloEntry, Clock.System.now())))
        var side = SideButton.None

        rule.setContent { TestAppShell(library, soloCatalog(), FakeDownloadManager(), sideButton = { side }) }
        rule.waitForIdle()
        rule.onNodeWithText("Solo Leveling").performMouseInput { click() }
        rule.waitForIdle()
        rule.onNodeWithText("In library ✓").assertExists()

        side = SideButton.Back
        rule.onNodeWithText("In library ✓").performMouseInput { click() }
        rule.waitForIdle()

        rule.onNodeWithTag(LIBRARY_GRID_TEST_TAG).assertExists()
        rule.onNodeWithText("In library ✓").assertDoesNotExist()
        val stillInLibrary = runBlocking { library.observeLibrary().first() }.map { it.entry.mangaId }
        assertEquals(listOf("manga-1"), stillInLibrary)

        // At the root back has nowhere to go, so only the consumed press keeps the cover
        // underneath from opening.
        rule.onNodeWithText("Solo Leveling").performMouseInput { click() }
        rule.waitForIdle()

        rule.onNodeWithTag(LIBRARY_GRID_TEST_TAG).assertExists()
        rule.onNodeWithText("In library ✓").assertDoesNotExist()
    }

    @Test
    fun mouseForwardButtonIsSwallowed() {
        val library = FakeLibraryRepository(listOf(LibraryItem(soloEntry, Clock.System.now())))

        rule.setContent {
            TestAppShell(library, soloCatalog(), FakeDownloadManager(), sideButton = { SideButton.Forward })
        }
        rule.waitForIdle()
        rule.onNodeWithText("Solo Leveling").performMouseInput { click() }
        rule.waitForIdle()

        rule.onNodeWithTag(LIBRARY_GRID_TEST_TAG).assertExists()
        rule.onNodeWithText("In library ✓").assertDoesNotExist()
    }

    // Esc-to-back listens on the window's bubbling key handler, which only sees an Esc no
    // focused child consumed; a focused search field must keep Esc for itself.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun escInFocusedSearchFieldIsConsumed() {
        var escReachedParent = false
        val focus = FocusRequester()

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                Box(
                    Modifier.onKeyEvent {
                        if (it.key == Key.Escape) escReachedParent = true
                        false
                    },
                ) {
                    KitSearchField(value = "", onValueChange = {}, placeholder = "Filter", onSearch = {}, focusRequester = focus)
                }
            }
        }
        rule.runOnIdle { focus.requestFocus() }
        rule.onNode(hasSetTextAction()).assertIsFocused()

        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()

        assertFalse(escReachedParent)
        rule.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    // The dropdown's filter is a search field too; Esc there must still close the menu, not
    // just drop the field's focus.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun escInAFilterableDropdownClosesTheMenu() {
        val options = (1..20).map { "Font $it" }

        rule.setContent {
            ProvideMangoTheme(MangoDark) { KitDropdown(selected = "Font 1", options = options, onSelect = {}) }
        }
        rule.onNodeWithText("Font 1").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Type to filter…").assertExists()

        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()

        rule.onNodeWithText("Type to filter…").assertDoesNotExist()
    }
}
