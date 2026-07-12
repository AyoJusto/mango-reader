package dev.mango.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.ChallengeSolver
import dev.mango.core.domain.DownloadManager
import dev.mango.core.domain.LibraryRepository

/**
 * [AppShell] with self-managed sidebar state and the default theme, so flow tests can drive
 * sidebar navigation without each hoisting the state themselves (production hoists it in
 * Main.kt for the Ctrl+S window hotkey).
 */
@Composable
fun TestAppShell(
    library: LibraryRepository,
    catalog: CatalogRepository,
    downloads: DownloadManager,
    challengeSolver: ChallengeSolver? = null,
    palette: PaletteState? = null,
) {
    var sidebarOpen by remember { mutableStateOf(false) }
    ProvideMangoTheme(MangoDark) {
        AppShell(
            library = library,
            catalog = catalog,
            downloads = downloads,
            challengeSolver = challengeSolver ?: remember { FakeChallengeSolver() },
            palette = palette ?: remember { PaletteState() },
            sidebarOpen = sidebarOpen,
            onSidebarChange = { sidebarOpen = it },
        )
    }
}

/** Opens the sidebar via the title-bar glyph and clicks the [label] nav item. */
fun ComposeContentTestRule.navigateVia(label: String) {
    onNodeWithTag(SIDEBAR_TOGGLE_TAG).performClick()
    waitForIdle()
    onNodeWithText(label).performClick()
    waitForIdle()
}
