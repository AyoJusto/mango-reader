package dev.mango.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import dev.mango.core.domain.LibraryItem
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.MangaStatus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import org.junit.Rule
import org.junit.Test

class PaletteFlowTest {
    @get:Rule
    val rule = createComposeRule()

    private val inPalette = hasAnyAncestor(hasTestTag(PALETTE_TEST_TAG))

    private fun libraryItems() = listOf(
        LibraryItem(
            MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling"),
            Clock.System.now()
        ),
        LibraryItem(
            MangaEntry(sourceId = "FlameComics", mangaId = "manga-2", title = "Omniscient Reader"),
            Clock.System.now()
        ),
        LibraryItem(
            MangaEntry(sourceId = "FlameComics", mangaId = "manga-3", title = "Tower of God"),
            Clock.System.now()
        ),
    )

    @Test
    fun openingThePaletteShowsScreenHits() {
        val library = FakeLibraryRepository(libraryItems())
        val palette = PaletteState()

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                AppShell(
                    library,
                    FakeCatalogRepository(),
                    FakeDownloadManager(),
                    palette = palette
                )
            }
        }
        rule.waitForIdle()

        palette.visible = true
        rule.waitForIdle()

        rule.onNodeWithText("Search everywhere…").performTextInput("Library")
        rule.waitForIdle()

        val screenHits = rule.onAllNodes(hasText("Library") and hasClickAction() and inPalette).fetchSemanticsNodes()
        assertTrue(screenHits.isNotEmpty(), "expected the Library screen hit to be showing")
    }

    @Test
    fun typingALibraryTitleFragmentNarrowsHitsToIt() {
        val library = FakeLibraryRepository(libraryItems())
        val palette = PaletteState()

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                AppShell(
                    library,
                    FakeCatalogRepository(),
                    FakeDownloadManager(),
                    palette = palette
                )
            }
        }
        rule.waitForIdle()
        palette.visible = true
        rule.waitForIdle()

        rule.onNodeWithText("Search everywhere…").performTextInput("Solo")
        rule.waitForIdle()

        rule.onNode(hasText("Solo Leveling") and inPalette).assertExists()
        rule.onNode(hasText("Tower of God") and inPalette).assertDoesNotExist()
        rule.onNode(hasText("Omniscient Reader") and inPalette).assertDoesNotExist()
    }

    @Test
    fun downMovesSelectionToTheSecondHitAndEnterOpensItsDetails() {
        val first = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val second = MangaEntry(sourceId = "FlameComics", mangaId = "manga-2", title = "Solo Prime")
        val library = FakeLibraryRepository(
            listOf(LibraryItem(first, Clock.System.now()), LibraryItem(second, Clock.System.now())),
        )
        val catalog = FakeCatalogRepository(
            details = mapOf(
                ("FlameComics" to "manga-1") to MangaDetails(entry = first, status = MangaStatus.ONGOING),
                ("FlameComics" to "manga-2") to MangaDetails(entry = second, status = MangaStatus.ONGOING),
            ),
            chapters = mapOf(
                ("FlameComics" to "manga-1") to emptyList(),
                ("FlameComics" to "manga-2") to emptyList(),
            ),
        )
        val palette = PaletteState()

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                AppShell(
                    library,
                    catalog,
                    FakeDownloadManager(),
                    palette = palette
                )
            }
        }
        rule.waitForIdle()
        palette.visible = true
        rule.waitForIdle()

        rule.onNodeWithText("Search everywhere…").performTextInput("Solo")
        rule.waitForIdle()

        rule.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        rule.waitForIdle()

        rule.onNodeWithText("In library ✓").assertExists()
        rule.onNodeWithText("Solo Prime").assertExists()
        rule.onNodeWithText("Solo Leveling").assertDoesNotExist()
    }

    @Test
    fun escapeClosesThePalette() {
        val library = FakeLibraryRepository(libraryItems())
        val palette = PaletteState()

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                AppShell(
                    library,
                    FakeCatalogRepository(),
                    FakeDownloadManager(),
                    palette = palette
                )
            }
        }
        rule.waitForIdle()
        palette.visible = true
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()

        assertFalse(palette.visible)
    }

    @Test
    fun footerHasNoKeyHints() {
        val palette = PaletteState()
        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                AppShell(
                    FakeLibraryRepository(libraryItems()),
                    FakeCatalogRepository(),
                    FakeDownloadManager(),
                    palette = palette
                )
            }
        }
        rule.waitForIdle()
        palette.visible = true
        rule.waitForIdle()

        rule.onNodeWithText("navigate", substring = true).assertDoesNotExist()
    }

    @Test
    fun rowsWithoutSubtitleShowNoCategoryLine() {
        val palette = PaletteState()
        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                AppShell(
                    FakeLibraryRepository(libraryItems()),
                    FakeCatalogRepository(),
                    FakeDownloadManager(),
                    palette = palette
                )
            }
        }
        rule.waitForIdle()
        palette.visible = true
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.Tab) }
        rule.onRoot().performKeyInput { pressKey(Key.Tab) }
        rule.waitForIdle()

        rule.onAllNodes(hasText("Actions") and inPalette).assertCountEquals(1)
    }

    @Test
    fun tabKeySwitchesToTheManhwaTabAndScreenHitsDisappear() {
        val library = FakeLibraryRepository(libraryItems())
        val palette = PaletteState()

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                AppShell(
                    library,
                    FakeCatalogRepository(),
                    FakeDownloadManager(),
                    palette = palette
                )
            }
        }
        rule.waitForIdle()
        palette.visible = true
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.Tab) }
        rule.waitForIdle()

        rule.onAllNodes(hasText("Library") and hasClickAction() and inPalette).assertCountEquals(0)
        rule.onNode(hasText("Solo Leveling") and inPalette).assertExists()
    }

    @Test
    fun everyRegisteredSettingsEntrySurfacesAPaletteHitAndSelectingOneNavigatesToSettings() {
        val library = FakeLibraryRepository(libraryItems())
        val palette = PaletteState()

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                AppShell(
                    library,
                    FakeCatalogRepository(),
                    FakeDownloadManager(),
                    palette = palette
                )
            }
        }
        rule.waitForIdle()

        palette.visible = true
        rule.waitForIdle()

        rule.onNodeWithText("Search everywhere…").performTextInput("Setting:")
        rule.waitForIdle()

        SETTINGS_ENTRIES.forEach { title ->
            rule.onNode(hasText("Setting: $title") and inPalette).assertExists()
        }

        rule.onNodeWithText("Setting:").performTextClearance()
        rule.waitForIdle()
        rule.onNodeWithText("Search everywhere…").performTextInput("Setting: Auto-scroll speed")
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        assertFalse(palette.visible)
        rule.onNodeWithText("Theme").assertExists()
    }

    @Test
    fun stripWidthAndHideCursorSettingsHitsNavigateToSettings() {
        val library = FakeLibraryRepository(libraryItems())
        val palette = PaletteState()

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                AppShell(
                    library,
                    FakeCatalogRepository(),
                    FakeDownloadManager(),
                    palette = palette
                )
            }
        }
        rule.waitForIdle()

        listOf("Strip width", "Hide cursor").forEach { title ->
            palette.visible = true
            rule.waitForIdle()

            rule.onNodeWithText("Search everywhere…").performTextInput("Setting: $title")
            rule.waitForIdle()

            rule.onRoot().performKeyInput { pressKey(Key.Enter) }
            rule.waitForIdle()

            assertFalse(palette.visible)
            rule.onNodeWithText("Theme").assertExists()
        }
    }

    @Test
    fun everyAccentPresetSurfacesAPaletteHitAndSelectingOneRecolorsTheAccent() {
        val library = FakeLibraryRepository(libraryItems())
        val palette = PaletteState()
        var appliedTheme: MangoTheme = MangoDark

        rule.setContent {
            var theme by remember { mutableStateOf(MangoDark) }
            ProvideMangoTheme(theme) {
                AppShell(
                    library,
                    FakeCatalogRepository(),
                    FakeDownloadManager(),
                    theme = theme,
                    onThemeChange = { theme = it; appliedTheme = it },
                    palette = palette,
                )
            }
        }
        rule.waitForIdle()

        palette.visible = true
        rule.waitForIdle()

        rule.onNodeWithText("Search everywhere…").performTextInput("Accent:")
        rule.waitForIdle()

        ACCENT_PRESETS.forEach { (label, _) ->
            rule.onNode(hasText("Accent: $label") and inPalette).assertExists()
        }

        rule.onNodeWithText("Accent:").performTextClearance()
        rule.waitForIdle()
        rule.onNodeWithText("Search everywhere…").performTextInput("Accent: Violet")
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        assertFalse(palette.visible)
        val violet = ACCENT_PRESETS.first { it.first == "Violet" }.second
        assertEquals(violet, appliedTheme.accent)
        assertEquals(MangoDark.bg0, appliedTheme.bg0)
    }

    @Test
    fun everyThemeInTheLibrarySurfacesADynamicPaletteHitAndSelectingOneAppliesIt() {
        val library = FakeLibraryRepository(libraryItems())
        val palette = PaletteState()
        val custom =
            MangoDark.copy(name = "Custom Theme", accent = ACCENT_PRESETS.first { it.first == "Violet" }.second)
        var appliedTheme: MangoTheme = MangoDark

        rule.setContent {
            var theme by remember { mutableStateOf(MangoDark) }
            ProvideMangoTheme(theme) {
                AppShell(
                    library,
                    FakeCatalogRepository(),
                    FakeDownloadManager(),
                    theme = theme,
                    onThemeChange = { theme = it; appliedTheme = it },
                    themeLibrary = listOf(MangoDark, custom),
                    palette = palette,
                )
            }
        }
        rule.waitForIdle()

        palette.visible = true
        rule.waitForIdle()

        rule.onNodeWithText("Search everywhere…").performTextInput("Theme:")
        rule.waitForIdle()

        rule.onNode(hasText("Theme: ${MangoDark.name}") and inPalette).assertExists()
        rule.onNode(hasText("Theme: ${custom.name}") and inPalette).assertExists()

        rule.onNodeWithText("Theme:").performTextClearance()
        rule.waitForIdle()
        rule.onNodeWithText("Search everywhere…").performTextInput("Theme: Custom Theme")
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        assertFalse(palette.visible)
        assertEquals(custom, appliedTheme)
    }

    @Test
    fun toggleSidebarActionSurfacesAPaletteHitAndRunningItOpensTheSidebar() {
        val library = FakeLibraryRepository(libraryItems())
        val palette = PaletteState()

        rule.setContent { TestAppShell(library, FakeCatalogRepository(), FakeDownloadManager(), palette = palette) }
        rule.waitForIdle()

        palette.visible = true
        rule.waitForIdle()

        rule.onNodeWithText("Search everywhere…").performTextInput("sidebar")
        rule.waitForIdle()

        rule.onNode(hasText("Toggle sidebar") and inPalette).assertExists()

        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        assertFalse(palette.visible)
        rule.onNodeWithTag(SIDEBAR_TEST_TAG).assertExists()
    }

    @Test
    fun checkForUpdatesActionSurfacesAPaletteHit() {
        val library = FakeLibraryRepository(libraryItems())
        val palette = PaletteState()

        rule.setContent { TestAppShell(library, FakeCatalogRepository(), FakeDownloadManager(), palette = palette) }
        rule.waitForIdle()

        palette.visible = true
        rule.waitForIdle()

        rule.onNodeWithText("Search everywhere…").performTextInput("for updates")
        rule.waitForIdle()

        rule.onNode(hasText("Check for updates") and inPalette).assertExists()
    }

    @Test
    fun clearSearchHistoryActionSurfacesAPaletteHit() {
        val library = FakeLibraryRepository(libraryItems())
        val palette = PaletteState()

        rule.setContent { TestAppShell(library, FakeCatalogRepository(), FakeDownloadManager(), palette = palette) }
        rule.waitForIdle()

        palette.visible = true
        rule.waitForIdle()

        rule.onNodeWithText("Search everywhere…").performTextInput("search history")
        rule.waitForIdle()

        rule.onNode(hasText("Clear search history") and inPalette).assertExists()
    }

    @Test
    fun manageCollectionsActionSurfacesAPaletteHitAndRunningItOpensTheDialog() {
        val library = FakeLibraryRepository(libraryItems())
        val palette = PaletteState()

        rule.setContent { TestAppShell(library, FakeCatalogRepository(), FakeDownloadManager(), palette = palette) }
        rule.waitForIdle()

        palette.visible = true
        rule.waitForIdle()

        rule.onNodeWithText("Search everywhere…").performTextInput("Manage collections")
        rule.waitForIdle()

        rule.onNode(hasText("Manage collections…") and inPalette).assertExists()

        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        assertFalse(palette.visible)
        rule.onNodeWithText("Manage collections").assertExists()
    }

    @Test
    fun everyCollectionSurfacesADynamicPaletteHitAndSelectingOneFiltersTheLibrary() {
        val filed = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val unfiled = MangaEntry(sourceId = "FlameComics", mangaId = "manga-2", title = "Tower of God")
        val library = FakeLibraryRepository(
            listOf(
                LibraryItem(filed, Clock.System.now(), collectionIds = setOf(1)),
                LibraryItem(unfiled, Clock.System.now()),
            ),
        )
        val palette = PaletteState()

        rule.setContent { TestAppShell(library, FakeCatalogRepository(), FakeDownloadManager(), palette = palette) }
        rule.waitForIdle()

        palette.visible = true
        rule.waitForIdle()

        rule.onNodeWithText("Search everywhere…").performTextInput("Collection:")
        rule.waitForIdle()

        rule.onNode(hasText("Collection: All") and inPalette).assertExists()
        rule.onNode(hasText("Collection: Reading") and inPalette).assertExists()

        rule.onNodeWithText("Collection:").performTextClearance()
        rule.waitForIdle()
        rule.onNodeWithText("Search everywhere…").performTextInput("Collection: Reading")
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        assertFalse(palette.visible)
        rule.onNodeWithText("Solo Leveling").assertExists()
        rule.onNodeWithText("Tower of God").assertDoesNotExist()
    }

    @Test
    fun aThrowingProviderDoesNotBlankTheGoodProvidersHits() {
        val state = PaletteState()
        state.visible = true
        val goodProvider = PaletteProvider { listOf(PaletteHit(category = "Good", title = "GoodHit", run = {})) }
        val badProvider = PaletteProvider { throw RuntimeException("boom") }
        val tabs = listOf(PaletteTab("All", listOf(goodProvider, badProvider)))

        rule.setContent { ProvideMangoTheme(MangoDark) { PaletteOverlay(state = state, tabs = tabs) } }
        rule.waitForIdle()

        rule.onNodeWithText("GoodHit").assertExists()
    }
}
