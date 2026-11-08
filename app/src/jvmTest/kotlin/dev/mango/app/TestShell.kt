package dev.mango.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.mango.core.domain.CatalogCache
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.ChallengeSolver
import dev.mango.core.domain.DownloadManager
import dev.mango.core.domain.LibraryRepository

@Composable
fun TestAppShell(
    library: LibraryRepository,
    catalog: CatalogRepository,
    downloads: DownloadManager,
    challengeSolver: ChallengeSolver? = null,
    palette: PaletteState? = null,
    catalogCache: CatalogCache = remember { FakeCatalogCache() },
    onLibraryChecked: (Long) -> Unit = {},
    searchHistory: List<SearchHistoryEntry> = emptyList(),
    onSearchHistoryChange: (List<SearchHistoryEntry>) -> Unit = {},
    motion: MangoMotion = MangoMotion(),
    navigator: Navigator? = null,
    sideButton: () -> SideButton = { SideButton.None },
) {
    val lastInputWasKeyboard = remember { mutableStateOf(false) }
    var sidebarOpen by remember { mutableStateOf(false) }
    var libraryView by remember { mutableStateOf(LIBRARY_VIEW_GRID) }
    var libraryCheckedAt by remember { mutableStateOf<Long?>(null) }
    var history by remember { mutableStateOf(searchHistory) }
    ProvideMangoTheme(MangoDark) {
        CompositionLocalProvider(
            LocalMangoMotion provides motion,
            LocalLastInputWasKeyboard provides lastInputWasKeyboard,
            LocalSideButton provides sideButton,
        ) {
            AppShell(
                library = library,
                catalog = catalog,
                downloads = downloads,
                challengeSolver = challengeSolver ?: remember { FakeChallengeSolver() },
                catalogCache = catalogCache,
                palette = palette ?: remember { PaletteState() },
                sidebarOpen = sidebarOpen,
                onSidebarChange = { sidebarOpen = it },
                libraryView = libraryView,
                onLibraryViewChange = { libraryView = it },
                libraryCheckedAt = libraryCheckedAt,
                onLibraryChecked = { libraryCheckedAt = it; onLibraryChecked(it) },
                searchHistory = history,
                onSearchHistoryChange = { history = it; onSearchHistoryChange(it) },
                navigator = navigator ?: remember { Navigator() },
                lastInputWasKeyboard = lastInputWasKeyboard,
            )
        }
    }
}

fun ComposeContentTestRule.navigateVia(label: String) {
    onNodeWithTag(SIDEBAR_TOGGLE_TAG).performClick()
    waitForIdle()
    onNodeWithText(label).performClick()
    waitForIdle()
    onNodeWithTag(SIDEBAR_TOGGLE_TAG).performClick()
    waitForIdle()
}
