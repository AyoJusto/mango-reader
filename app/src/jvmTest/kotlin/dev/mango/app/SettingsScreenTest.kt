package dev.mango.app

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Flow test for the M4.4a Settings screen: theme names are listed and picking one fires the
 * callback. Style mirrors ExtensionsScreenTest.
 */
class SettingsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun listsThemeNamesAndClickingOneFiresTheCallback() {
        var selected: String? = null

        rule.setContent {
            MangoTheme {
                SettingsScreenContent(
                    themeNames = Themes.schemes.keys.toList(),
                    currentTheme = Themes.DEFAULT,
                    onSelectTheme = { selected = it },
                )
            }
        }

        Themes.schemes.keys.forEach { name -> rule.onNodeWithText(name).assertExists() }

        rule.onNodeWithText("midnight").performClick()
        rule.waitForIdle()

        assertEquals("midnight", selected)
    }

    // Completeness test for the R3 registry: every SETTINGS_ENTRIES title must be discoverable
    // on the rendered screen (substring match — the auto-scroll label carries a live value
    // suffix), so a future registry entry with no matching UI text fails loudly here.
    @Test
    fun everyRegisteredSettingsEntryIsRenderedOnScreen() {
        rule.setContent {
            MangoTheme {
                SettingsScreenContent(
                    themeNames = Themes.schemes.keys.toList(),
                    currentTheme = Themes.DEFAULT,
                    onSelectTheme = {},
                )
            }
        }

        SETTINGS_ENTRIES.forEach { title ->
            val matches = rule.onAllNodes(hasText(title, substring = true)).fetchSemanticsNodes()
            assertTrue(matches.isNotEmpty(), "expected a node containing \"$title\" for registered setting \"$title\"")
        }
    }
}
