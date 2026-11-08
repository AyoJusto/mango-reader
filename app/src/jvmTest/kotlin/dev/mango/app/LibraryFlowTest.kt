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

    @Test
    fun toggleLibraryViewActionSurfacesAPaletteHitAndRunningItSwitchesTheView() {
        val library = FakeLibraryRepository(libraryItems())
        val palette = PaletteState()

        rule.setContent { TestAppShell(library, FakeCatalogRepository(), FakeDownloadManager(), palette = palette) }
        rule.waitForIdle()

        rule.onNodeWithTag(LIBRARY_GRID_TEST_TAG).assertExists()

        palette.visible = true
        rule.waitForIdle()

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

    private fun inProgressItems() = listOf(
        LibraryItem(
            MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling"),
            Clock.System.now(),
            chapterCount = 10,
            unreadCount = 7,
        ),
    )

    private fun showLibrary(items: List<LibraryItem>, libraryView: String = LIBRARY_VIEW_GRID, checkedAt: Long? = null) {
        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                LibraryScreenContent(
                    items = items,
                    libraryView = libraryView,
                    onOpenDetails = {},
                    checkedAt = checkedAt,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun gridShowsNoChapterOrPercentUnderACover() {
        showLibrary(inProgressItems())

        rule.onNodeWithText("Ch. ", substring = true).assertDoesNotExist()
        rule.onNodeWithText("%", substring = true).assertDoesNotExist()
    }

    @Test
    fun headerShowsNoSeriesCount() {
        showLibrary(inProgressItems(), checkedAt = Clock.System.now().toEpochMilliseconds())

        rule.onNodeWithText("series", substring = true).assertDoesNotExist()
        rule.onNodeWithText("All · 1").assertExists()
    }

    @Test
    fun finishedSeriesShowsNoCompletedCaptionInGrid() {
        val finished = inProgressItems().map { it.copy(unreadCount = 0) }

        showLibrary(finished)
        rule.onNodeWithText("Completed").assertDoesNotExist()
    }

    @Test
    fun finishedSeriesShowsCompletedInList() {
        val finished = inProgressItems().map { it.copy(unreadCount = 0) }

        showLibrary(finished, LIBRARY_VIEW_LIST)
        rule.onNodeWithText("Completed").assertExists()
    }

    @Test
    fun listRowShowsNoPercentColumn() {
        showLibrary(inProgressItems(), LIBRARY_VIEW_LIST)

        rule.onNodeWithText("%", substring = true).assertDoesNotExist()
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

        rule.onNodeWithText("Reading", useUnmergedTree = true).performTouchInput { doubleClick() }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction(), useUnmergedTree = true).performTextReplacement("Currently Reading")
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        rule.onNodeWithText("Currently Reading · 1").assertExists()

        rule.onAllNodes(hasText("✕"), useUnmergedTree = true).onFirst().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Currently Reading · 1").assertDoesNotExist()
        val afterDelete = runBlocking { library.observeCollections().first() }
        assertEquals(listOf("Dropped"), afterDelete.map { it.name })
        assertTrue(afterDelete.single().isDefault)

        rule.onNodeWithText("＋ New collection", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction(), useUnmergedTree = true).performTextInput("Finished")
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        rule.onNodeWithText("Finished · 0").assertExists()
    }

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
