package dev.mango.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.assertEquals
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

        // Board 09's Theme row sub-copy is "<name> · yours to edit", not the bare theme name —
        // this replaces the old bare-name assertion (display-copy change, not a behavior change).
        rule.onNodeWithText("${MangoDark.name} · yours to edit").assertExists()
        rule.onNodeWithText("Export .json").assertExists()
        rule.onNodeWithText("Import…").assertExists()
        ACCENT_PRESETS.forEach { (label, _) ->
            rule.onNodeWithTag("accent-swatch-$label").assertExists()
        }
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
}
