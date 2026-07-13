package dev.mango.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import dev.mango.core.domain.CollectionInfo
import dev.mango.core.domain.LibraryItem
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.MangaStatus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
        LibraryItem(
            MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling"),
            Clock.System.now()
        ),
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

    @Test
    fun aSeriesWithNewChaptersShowsThePlusNewCaptionInTheGrid() {
        val items = listOf(
            LibraryItem(
                MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling"),
                Clock.System.now(),
                newCount = 3,
            ),
        )

        rule.setContent { ProvideMangoTheme(MangoDark) { LibraryScreenContent(items = items, onOpenDetails = {}) } }
        rule.waitForIdle()

        rule.onNodeWithText("+3 new").assertExists()
    }

    @Test
    fun aNonNullCheckedAtShowsCheckedInTheHeaderCaption() {
        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                LibraryScreenContent(
                    items = libraryItems(),
                    onOpenDetails = {},
                    checkedAt = Clock.System.now().toEpochMilliseconds(),
                )
            }
        }
        rule.waitForIdle()

        rule.onNode(hasText("checked", substring = true)).assertExists()
    }

    @Test
    fun clickingTheRefreshButtonInvokesTheCallback() {
        var invoked = false

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                LibraryScreenContent(items = libraryItems(), onOpenDetails = {}, onCheckForUpdates = { invoked = true })
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag("library-refresh").performClick()
        rule.waitForIdle()

        assertTrue(invoked)
    }

    // AppShell-level wiring: clicking the header's refresh button must run a real library-wide
    // check through LibraryUpdater — the call count on the fake catalog is the observable proof
    // (TestAppShell's onLibraryChecked isn't asserted separately here since the call count
    // already proves checkForUpdates ran end to end through the shell).
    @Test
    fun clickingLibraryRefreshRunsALibraryWideUpdateCheckThroughAppShell() {
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val library = FakeLibraryRepository(listOf(LibraryItem(entry, Clock.System.now())))
        val catalog = FakeCatalogRepository(
            details = mapOf(("FlameComics" to "manga-1") to MangaDetails(entry = entry, status = MangaStatus.ONGOING)),
            chapters = mapOf(("FlameComics" to "manga-1") to emptyList()),
        )
        val catalogCache = FakeCatalogCache()

        rule.setContent { TestAppShell(library, catalog, FakeDownloadManager(), catalogCache = catalogCache) }
        rule.waitForIdle()

        rule.onNodeWithTag("library-refresh").performClick()
        rule.waitForIdle()

        assertTrue(catalog.chaptersCallCount >= 1)
    }

    // Clicking a shelf chip narrows the grid to its members; "All" always shows everything
    // regardless of membership.
    @Test
    fun clickingACollectionChipFiltersToItsMembersAndAllShowsEverythingAgain() {
        val collectionA = CollectionInfo(1, "Reading", 0, true)
        val collectionB = CollectionInfo(2, "Dropped", 1, false)
        val filed = LibraryItem(
            MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling"),
            Clock.System.now(),
            collectionIds = setOf(1),
        )
        val unfiled = LibraryItem(
            MangaEntry(sourceId = "FlameComics", mangaId = "manga-2", title = "Tower of God"),
            Clock.System.now(),
        )

        rule.setContent {
            var selected by remember { mutableStateOf<Long?>(null) }
            ProvideMangoTheme(MangoDark) {
                LibraryScreenContent(
                    items = listOf(filed, unfiled),
                    collections = listOf(collectionA, collectionB),
                    selectedCollectionId = selected,
                    onSelectCollection = { selected = it },
                    onOpenDetails = {},
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Solo Leveling").assertExists()
        rule.onNodeWithText("Tower of God").assertExists()

        rule.onNodeWithText("Reading · 1").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Solo Leveling").assertExists()
        rule.onNodeWithText("Tower of God").assertDoesNotExist()

        rule.onNodeWithText("All · 2").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Solo Leveling").assertExists()
        rule.onNodeWithText("Tower of God").assertExists()
    }

    // End to end through AppShell: the manage-collections dialog's rename, delete (with the
    // default-promotion fallback), and create all round-trip into the chip row.
    @Test
    fun manageCollectionsDialogRenamesDeletesTheDefaultAndCreates() {
        val library = FakeLibraryRepository(
            listOf(
                LibraryItem(
                    MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling"),
                    Clock.System.now(),
                    collectionIds = setOf(1),
                ),
            ),
        )
        runBlocking { library.createCollection("Dropped") }

        rule.setContent { TestAppShell(library, FakeCatalogRepository(), FakeDownloadManager()) }
        rule.waitForIdle()

        rule.onNodeWithText("⋯").performClick()
        rule.waitForIdle()

        // The dialog panel's own click-swallowing modifier (so a click on it doesn't fall
        // through to the scrim's dismiss) merges every descendant into one semantics node, so
        // everything inside the dialog below is looked up against the unmerged tree instead.

        // Rename the default collection ("Reading", inside the dialog row — the chip row's own
        // text always carries a " · N" suffix, so it can't collide with this exact match).
        rule.onNodeWithText("Reading", useUnmergedTree = true).performTouchInput { doubleClick() }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction(), useUnmergedTree = true).performTextReplacement("Currently Reading")
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        rule.onNodeWithText("Currently Reading · 1").assertExists()

        // Delete the (renamed) default; the fake promotes the remaining collection to default.
        // Both rows have a "✕" (both are deletable while two collections remain), so the first
        // one in tree order is targeted — "Currently Reading" (still position 0) sorts first.
        rule.onAllNodes(hasText("✕"), useUnmergedTree = true).onFirst().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Currently Reading · 1").assertDoesNotExist()
        val afterDelete = runBlocking { library.observeCollections().first() }
        assertEquals(listOf("Dropped"), afterDelete.map { it.name })
        assertTrue(afterDelete.single().isDefault)

        // Create a new shelf via the dialog's own affordance: the footer appends an empty row
        // already in rename mode, so typing straight into it and pressing Enter commits it —
        // no separate dialog anywhere.
        rule.onNodeWithText("＋ New collection", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction(), useUnmergedTree = true).performTextInput("Finished")
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        rule.onNodeWithText("Finished · 0").assertExists()
    }

    // The Library "＋" chip creates inline: no NewCollectionDialog, just a text field swapped in
    // for the chip itself.
    @Test
    fun libraryPlusChipTurnsIntoAnInlineFieldThatCreatesTheShelf() {
        val library = FakeLibraryRepository(libraryItems())

        rule.setContent { TestAppShell(library, FakeCatalogRepository(), FakeDownloadManager()) }
        rule.waitForIdle()

        rule.onNodeWithText("＋").performClick()
        rule.waitForIdle()

        rule.onNode(hasSetTextAction()).performTextInput("Dropped")
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        rule.onNodeWithText("Dropped · 0").assertExists()
    }
}
