package dev.mango.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import dev.mango.core.domain.LibraryItem
import dev.mango.core.domain.MangaEntry
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Clock
import org.junit.Rule
import org.junit.Test

/**
 * End-to-end Library view-toggle flows through [AppShell] (via [TestAppShell]'s hoisted
 * [LIBRARY_VIEW_GRID]/[LIBRARY_VIEW_LIST] state, same idiom as its sidebar-state hoisting).
 */
class LibraryFlowTest {
    @get:Rule
    val rule = createComposeRule()

    private fun libraryItems() = listOf(
        LibraryItem(MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling"), Clock.System.now()),
    )

    @Test
    fun libraryStartsInGridViewAndTheSegmentedControlSwitchesItToList() {
        val library = FakeLibraryRepository(libraryItems())

        rule.setContent { TestAppShell(library, FakeCatalogRepository(), FakeDownloadManager()) }
        rule.waitForIdle()

        rule.onNodeWithTag(LIBRARY_GRID_TEST_TAG).assertExists()

        rule.onNodeWithText("List").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(LIBRARY_LIST_TEST_TAG).assertExists()
    }

    // Completeness test for the actions registry: the library-view toggle must surface a palette
    // hit, and running it must actually flip the Library screen's rendered view.
    @Test
    fun toggleLibraryViewActionSurfacesAPaletteHitAndRunningItSwitchesTheView() {
        val library = FakeLibraryRepository(libraryItems())
        val palette = PaletteState()

        rule.setContent { TestAppShell(library, FakeCatalogRepository(), FakeDownloadManager(), palette = palette) }
        rule.waitForIdle()

        rule.onNodeWithTag(LIBRARY_GRID_TEST_TAG).assertExists()

        palette.visible = true
        rule.waitForIdle()

        // "toggle library" is a subsequence of no other hit title (in particular, not of the
        // "Setting: Library view" hit the settings registry also produces — that title has no
        // 'o' at all), so the action is both proven present and left as the single (selected)
        // hit for Enter below.
        rule.onNodeWithText("Search everywhere…").performTextInput("toggle library")
        rule.waitForIdle()

        rule.onNodeWithText("Toggle library view").assertExists()

        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        assertFalse(palette.visible)
        rule.onNodeWithTag(LIBRARY_LIST_TEST_TAG).assertExists()
    }

    @Test
    fun settingsShowsTheLibraryViewControlAndTogglingItPersistsToTheCallback() {
        var applied: String? = null

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                SettingsScreenContent(
                    theme = MangoDark,
                    onThemeChange = {},
                    libraryView = LIBRARY_VIEW_GRID,
                    onLibraryViewChange = { applied = it },
                )
            }
        }

        rule.onNodeWithTag(settingsEntryTag("Library view")).assertExists()

        rule.onNodeWithText("List").performClick()
        rule.waitForIdle()

        assertEquals(LIBRARY_VIEW_LIST, applied)
    }
}
