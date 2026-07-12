package dev.mango.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
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

/**
 * End-to-end palette flows through [AppShell], backed by [FakeLibraryRepository]. The
 * underlying screen (Library, by default) stays composed behind the scrim, so every lookup here
 * is scoped to [PALETTE_TEST_TAG]'s subtree — otherwise a hit title that's also a library grid
 * caption (e.g. "Solo Leveling") would match twice and fail with an ambiguous-node error.
 */
class PaletteFlowTest {
    @get:Rule
    val rule = createComposeRule()

    private val inPalette = hasAnyAncestor(hasTestTag(PALETTE_TEST_TAG))

    private fun libraryItems() = listOf(
        LibraryItem(MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling"), Clock.System.now()),
        LibraryItem(MangaEntry(sourceId = "FlameComics", mangaId = "manga-2", title = "Omniscient Reader"), Clock.System.now()),
        LibraryItem(MangaEntry(sourceId = "FlameComics", mangaId = "manga-3", title = "Tower of God"), Clock.System.now()),
    )

    @Test
    fun openingThePaletteShowsScreenHits() {
        val library = FakeLibraryRepository(libraryItems())
        val palette = PaletteState()

        rule.setContent { ProvideMangoTheme(MangoDark) { AppShell(library, FakeCatalogRepository(), FakeDownloadManager(), palette = palette) } }
        rule.waitForIdle()

        palette.visible = true
        rule.waitForIdle()

        // Narrow to a query only the "Library" screen hit matches (no accent, manhwa, or
        // setting title shares that subsequence): the unfiltered "All" list sorts Appearance
        // and Manhwa hits ahead of Screens alphabetically, so an unnarrowed Screens hit can sit
        // outside the LazyColumn's composed viewport despite being a real candidate.
        rule.onNodeWithText("Search everywhere…").performTextInput("Library")
        rule.waitForIdle()

        // "Screens" is the category label the Screens provider stamps on every hit; nothing
        // else in the app renders that word, so its presence alone proves the screen hit is showing.
        val screenHits = rule.onAllNodes(hasText("Screens") and inPalette).fetchSemanticsNodes()
        assertTrue(screenHits.isNotEmpty(), "expected the Screens-category hit to be showing")
    }

    @Test
    fun typingALibraryTitleFragmentNarrowsHitsToIt() {
        val library = FakeLibraryRepository(libraryItems())
        val palette = PaletteState()

        rule.setContent { ProvideMangoTheme(MangoDark) { AppShell(library, FakeCatalogRepository(), FakeDownloadManager(), palette = palette) } }
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
        // both manga get canned details so a broken Down handler fails the title assertions
        // below (the wrong Details renders) instead of blowing up on a missing canned entry
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

        rule.setContent { ProvideMangoTheme(MangoDark) { AppShell(library, catalog, FakeDownloadManager(), palette = palette) } }
        rule.waitForIdle()
        palette.visible = true
        rule.waitForIdle()

        // "Solo" fuzzy-matches exactly these two library titles (no screen, accent, or setting
        // name has that subsequence); equal scores tie-break by title, so hits are
        // [Solo Leveling, Solo Prime] and Down must move the selection to the SECOND one.
        rule.onNodeWithText("Search everywhere…").performTextInput("Solo")
        rule.waitForIdle()

        rule.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        rule.waitForIdle()

        // Details of the second hit: "In library — remove" is Details-only text, and the two
        // title assertions distinguish Solo Prime's Details from Solo Leveling's.
        rule.onNodeWithText("In library — remove").assertExists()
        rule.onNodeWithText("Solo Prime").assertExists()
        rule.onNodeWithText("Solo Leveling").assertDoesNotExist()
    }

    @Test
    fun escapeClosesThePalette() {
        val library = FakeLibraryRepository(libraryItems())
        val palette = PaletteState()

        rule.setContent { ProvideMangoTheme(MangoDark) { AppShell(library, FakeCatalogRepository(), FakeDownloadManager(), palette = palette) } }
        rule.waitForIdle()
        palette.visible = true
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()

        assertFalse(palette.visible)
    }

    @Test
    fun tabKeySwitchesToTheManhwaTabAndScreenHitsDisappear() {
        val library = FakeLibraryRepository(libraryItems())
        val palette = PaletteState()

        rule.setContent { ProvideMangoTheme(MangoDark) { AppShell(library, FakeCatalogRepository(), FakeDownloadManager(), palette = palette) } }
        rule.waitForIdle()
        palette.visible = true
        rule.waitForIdle()

        // tabs are ["All", "Manhwa", "Actions"]; one Tab from "All" lands on "Manhwa".
        rule.onRoot().performKeyInput { pressKey(Key.Tab) }
        rule.waitForIdle()

        rule.onAllNodes(hasText("Screens") and inPalette).assertCountEquals(0)
        rule.onNode(hasText("Solo Leveling") and inPalette).assertExists()
    }

    // Completeness test for the settings registry: every SETTINGS_ENTRIES title must surface a
    // hit in the palette, so a future registry entry with no matching provider output fails
    // loudly here. Also covers the end-to-end behavior: selecting the "Setting: Auto-scroll
    // speed" hit navigates to the Settings screen.
    @Test
    fun everyRegisteredSettingsEntrySurfacesAPaletteHitAndSelectingOneNavigatesToSettings() {
        val library = FakeLibraryRepository(libraryItems())
        val palette = PaletteState()

        rule.setContent { ProvideMangoTheme(MangoDark) { AppShell(library, FakeCatalogRepository(), FakeDownloadManager(), palette = palette) } }
        rule.waitForIdle()

        palette.visible = true
        rule.waitForIdle()

        // Narrow to the "Setting:" prefix first (colon included): with the unfiltered "All"
        // list, the LazyColumn only composes what's within the viewport, and library/screen
        // hits sort ahead of "Settings" alphabetically, so an unnarrowed entry can be off-screen
        // and absent from the semantics tree despite being a real candidate. The colon also
        // excludes the Screens-category "Settings" hit itself (its title has no colon), which
        // would otherwise occupy a viewport slot ahead of the alphabetically-last entries.
        rule.onNodeWithText("Search everywhere…").performTextInput("Setting:")
        rule.waitForIdle()

        SETTINGS_ENTRIES.forEach { title ->
            rule.onNode(hasText("Setting: $title") and inPalette).assertExists()
        }

        // Narrow further to the single "Setting: Auto-scroll speed" hit and run it.
        rule.onNodeWithText("Setting:").performTextClearance()
        rule.waitForIdle()
        rule.onNodeWithText("Search everywhere…").performTextInput("Setting: Auto-scroll speed")
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        // Palette closes on run, and "Theme" is the Settings screen's own heading text — no
        // other screen or palette hit renders that exact string — so its presence alone proves
        // the hit navigated to Settings.
        assertFalse(palette.visible)
        rule.onNodeWithText("Theme").assertExists()
    }

    // New-settings coverage (board 09): both new reader settings must be reachable exactly like
    // any other registered setting — a hit exists and running it navigates to Settings.
    @Test
    fun stripWidthAndHideCursorSettingsHitsNavigateToSettings() {
        val library = FakeLibraryRepository(libraryItems())
        val palette = PaletteState()

        rule.setContent { ProvideMangoTheme(MangoDark) { AppShell(library, FakeCatalogRepository(), FakeDownloadManager(), palette = palette) } }
        rule.waitForIdle()

        listOf("Strip width", "Hide cursor").forEach { title ->
            palette.visible = true
            rule.waitForIdle()

            // Narrows to the single matching hit; no separate existence assertion first (the
            // query text now equals the hit's own title, so the input field's own EditableText
            // would double-match a hasText lookup) — running it and observing the navigation
            // below is proof enough it existed, same idiom as the Auto-scroll-speed run above.
            rule.onNodeWithText("Search everywhere…").performTextInput("Setting: $title")
            rule.waitForIdle()

            rule.onRoot().performKeyInput { pressKey(Key.Enter) }
            rule.waitForIdle()

            assertFalse(palette.visible)
            // "Theme" is the Settings screen's own row title, same uniqueness rationale as the
            // completeness test above — it proves the hit navigated to Settings.
            rule.onNodeWithText("Theme").assertExists()
        }
    }

    // Completeness test for the accent registry: every ACCENT_PRESETS label must surface an
    // "Accent: <label>" hit under the "Appearance" category, so a future preset with no
    // matching provider output fails loudly here. Also covers the end-to-end behavior: picking
    // one recolors the current theme's accent and leaves every other token untouched.
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

        // Narrow to the "Accent:" prefix first — same rationale as the settings completeness
        // test above: the unfiltered "All" list only composes what's within the viewport.
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

    // Completeness test for the one-off actions registry: the sidebar toggle must surface a
    // palette hit, and running it must actually open the sidebar (search-everywhere rule).
    @Test
    fun toggleSidebarActionSurfacesAPaletteHitAndRunningItOpensTheSidebar() {
        val library = FakeLibraryRepository(libraryItems())
        val palette = PaletteState()

        rule.setContent { TestAppShell(library, FakeCatalogRepository(), FakeDownloadManager(), palette = palette) }
        rule.waitForIdle()

        palette.visible = true
        rule.waitForIdle()

        // "sidebar" is a subsequence of no other hit title, so the action is both proven
        // present and left as the single (selected) hit for Enter below. The query text itself
        // never equals the full hit title, so the exact-text lookup can't collide with it.
        rule.onNodeWithText("Search everywhere…").performTextInput("sidebar")
        rule.waitForIdle()

        rule.onNode(hasText("Toggle sidebar") and inPalette).assertExists()

        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        assertFalse(palette.visible)
        rule.onNodeWithTag(SIDEBAR_TEST_TAG).assertExists()
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
