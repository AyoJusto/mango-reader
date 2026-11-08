package dev.mango.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import org.junit.Rule
import org.junit.Test

class FocusRingTest {
    @get:Rule
    val rule = createComposeRule()

    private val hasRing = SemanticsMatcher.keyIsDefined(FocusRingKey)

    private fun startShell() {
        rule.setContent { TestAppShell(FakeLibraryRepository(), FakeCatalogRepository(), FakeDownloadManager()) }
        rule.waitForIdle()
    }

    // Focus starts on the sidebar glyph so the key event has a focused node to dispatch to.
    private fun pressTab() {
        rule.onNodeWithTag(SIDEBAR_TOGGLE_TAG).requestFocus()
        rule.onNodeWithTag(SIDEBAR_TOGGLE_TAG).performKeyInput { pressKey(Key.Tab) }
        rule.waitForIdle()
    }

    @Test
    fun clickingAButtonShowsNoRing() {
        startShell()

        rule.onNodeWithTag(SIDEBAR_TOGGLE_TAG).performClick()
        rule.waitForIdle()

        rule.onAllNodes(hasRing).assertCountEquals(0)
    }

    @Test
    fun tabbingShowsTheRingOnTheFocusedControl() {
        startShell()

        pressTab()
        rule.waitForIdle()

        rule.onAllNodes(hasRing).assertCountEquals(1)
    }

    @Test
    fun aClickAfterTabbingHidesTheRing() {
        startShell()
        pressTab()
        rule.waitForIdle()

        rule.onNodeWithTag(SIDEBAR_TOGGLE_TAG).performClick()
        rule.waitForIdle()

        rule.onAllNodes(hasRing).assertCountEquals(0)
    }

    // The keyboard flag is pinned on so these exercise the focus rules alone.
    private fun setKeyboardContent(content: @Composable () -> Unit) {
        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                CompositionLocalProvider(LocalLastInputWasKeyboard provides mutableStateOf(true)) { content() }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun aFocusedSliderShowsTheRing() {
        startShell()
        rule.navigateVia("Settings")
        val slider = rule.onNodeWithTag(settingsEntryTag("Strip width"))

        slider.requestFocus()
        slider.performKeyInput { pressKey(Key.A) }
        rule.waitForIdle()

        rule.onAllNodes(hasRing).assertCountEquals(1)
    }

    @Test
    fun aFocusedCollectionsPickerRowShowsTheRing() {
        setKeyboardContent {
            AddToCollectionsPicker(
                expanded = true,
                onDismissRequest = {},
                rows = listOf(CollectionCheckboxRow(1, "Favorites", isDefault = false, checked = false)),
                onToggle = {},
                onCreateAndFile = {},
                inLibrary = false,
                onRemoveFromLibrary = {},
            )
        }

        rule.onNodeWithText("Favorites").requestFocus()
        rule.waitForIdle()

        rule.onAllNodes(hasRing).assertCountEquals(1)
    }

    @Test
    fun focusingAChildInsideARowRingsOnlyTheChild() {
        setKeyboardContent {
            Box(Modifier.keyboardFocusRing(RectangleShape).clickable {}.padding(8.dp)) {
                Box(Modifier.size(20.dp).keyboardFocusRing(RectangleShape).clickable {}.testTag("child"))
            }
        }

        rule.onNodeWithTag("child").requestFocus()
        rule.waitForIdle()

        rule.onAllNodes(hasRing, useUnmergedTree = true).assertCountEquals(1)
    }
}
