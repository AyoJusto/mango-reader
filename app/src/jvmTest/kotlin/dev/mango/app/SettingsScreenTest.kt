package dev.mango.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Flow test for the Settings screen: the current theme's name and controls are shown, and
 * picking an accent swatch fires the callback. Style mirrors ExtensionsScreenTest.
 */
class SettingsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun showsTheCurrentThemeNameExportImportButtonsAndAccentSwatches() {
        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                SettingsScreenContent(theme = MangoDark, onThemeChange = {})
            }
        }

        // The dropdown is the only place the name shows.
        rule.onAllNodesWithText(MangoDark.name).assertCountEquals(1)
        rule.onNodeWithText("yours to edit", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Export .json").assertExists()
        rule.onNodeWithText("Import…").assertExists()
        ACCENT_PRESETS.forEach { (label, _) ->
            rule.onNodeWithTag("accent-swatch-$label").assertExists()
        }
    }

    @Test
    fun rowsCarryNoSubtitlesAndSliderValuesCarryTheirUnit() {
        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                SettingsScreenContent(theme = MangoDark, onThemeChange = {})
            }
        }

        listOf(
            "Recolors every accent token instantly",
            "Applies to the interface, not the pages",
            "Width of the centered reading column",
            "dp/s while auto-scroll (A) is running",
            "Blank the mouse cursor with the reader overlay",
        ).forEach { rule.onNodeWithText(it).assertDoesNotExist() }
        rule.onNodeWithText("120 dp/s").assertExists()
        rule.onNodeWithText("880 dp").assertExists()
    }

    // Structure smoke test for board 09: the three micro-label group headers must render.
    @Test
    fun showsAllThreeGroupHeaders() {
        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                SettingsScreenContent(theme = MangoDark, onThemeChange = {})
            }
        }

        rule.onNodeWithText("APPEARANCE").assertExists()
        rule.onNodeWithText("READER").assertExists()
        rule.onNodeWithText("GENERAL").assertExists()
    }

    @Test
    fun clickingAnAccentSwatchFiresTheCallbackWithOnlyTheAccentChanged() {
        var applied: MangoTheme? = null

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                SettingsScreenContent(theme = MangoDark, onThemeChange = { applied = it })
            }
        }

        rule.onNodeWithTag("accent-swatch-Violet").performClick()
        rule.waitForIdle()

        val violet = ACCENT_PRESETS.first { it.first == "Violet" }.second
        assertEquals(violet, applied?.accent)
        assertEquals(MangoDark.bg0, applied?.bg0)
    }

    // Completeness test for the settings registry: every SETTINGS_ENTRIES title must back a
    // real control on the rendered screen via its settingsEntryTag (not the visible label —
    // button wording is free to change), so a future registry entry with no matching control
    // fails loudly here.
    @Test
    fun everyRegisteredSettingsEntryHasARenderedControl() {
        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                SettingsScreenContent(theme = MangoDark, onThemeChange = {})
            }
        }

        SETTINGS_ENTRIES.forEach { title ->
            rule.onNodeWithTag(settingsEntryTag(title)).assertExists()
        }
    }

    @Test
    fun selectingAThemeFromTheDropdownAppliesThePristineLibraryEntry() {
        val custom = MangoDark.copy(name = "Custom Theme", accent = ACCENT_PRESETS.first { it.first == "Violet" }.second)
        var applied: MangoTheme? = null

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                SettingsScreenContent(
                    theme = MangoDark,
                    onThemeChange = { applied = it },
                    themeLibrary = listOf(MangoDark, custom),
                )
            }
        }

        rule.onNodeWithTag(settingsEntryTag("Theme")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Custom Theme").performClick()
        rule.waitForIdle()

        assertEquals(custom, applied)
    }

    @Test
    fun removeIsDisabledWhenTheActiveThemeIsMangoDark() {
        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                SettingsScreenContent(
                    theme = MangoDark,
                    onThemeChange = {},
                    onThemeDelete = { error("Remove must not fire while Mango Dark is active") },
                )
            }
        }

        rule.onNodeWithTag(settingsEntryTag("Remove theme")).assertIsNotEnabled()
    }

    @Test
    fun removeFiresOnThemeDeleteWhenAnImportedThemeIsActive() {
        val custom = MangoDark.copy(name = "Custom Theme")
        var deleted = false

        rule.setContent {
            ProvideMangoTheme(custom) {
                SettingsScreenContent(
                    theme = custom,
                    onThemeChange = {},
                    themeLibrary = listOf(MangoDark, custom),
                    onThemeDelete = { deleted = true },
                )
            }
        }

        rule.onNodeWithTag(settingsEntryTag("Remove theme")).assertIsEnabled().performClick()
        rule.waitForIdle()

        assertTrue(deleted)
    }
}
