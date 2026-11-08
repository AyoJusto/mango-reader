package dev.mango.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
}
