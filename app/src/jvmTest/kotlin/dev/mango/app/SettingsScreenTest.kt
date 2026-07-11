package dev.mango.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.assertEquals
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
}
