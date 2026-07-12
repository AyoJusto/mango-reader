package dev.mango.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mango.core.domain.AvailableSource
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.ChallengeSolver
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.DownloadManager
import dev.mango.core.domain.ExtensionRepo
import dev.mango.core.domain.LibraryRepository
import kotlinx.coroutines.launch

/** Used only when a caller doesn't wire a real registry (e.g. tests exercising other tabs). */
private object NoOpExtensionRepo : ExtensionRepo {
    override suspend fun available(): List<AvailableSource> = emptyList()
    override suspend fun install(source: AvailableSource) = Unit
}

/** Used only when a caller doesn't wire the real solver (tests exercising non-challenge flows). */
private object NoOpChallengeSolver : ChallengeSolver {
    override suspend fun solve(sourceId: String, url: String): Boolean = false
}

/**
 * Navigation state for the app shell. Hand-rolled — no nav library: the tree is a handful of
 * flat cases, not worth a dependency.
 */
sealed interface Screen {
    data object Library : Screen
    data object Search : Screen
    data object Browse : Screen
    data object Downloads : Screen
    data object Extensions : Screen
    data object Settings : Screen
    data class Details(val sourceId: String, val mangaId: String, val fromBrowse: Boolean) : Screen
    data class Reader(
        val sourceId: String,
        val mangaId: String,
        val chapterId: String,
        // sorted ascending by number at construction (the only construction site sorts below) so
        // the reader can walk it directly for next/prev without re-sorting on every navigation
        val chapters: List<Chapter>,
    ) : Screen
}

/**
 * The whole application UI: a left rail for the two top-level tabs (Library, Browse) plus
 * drill-down screens (Details, Reader) that replace the content area. Screens talk only to
 * the repository ports passed in here — never engine or DB types (CLAUDE.md boundary).
 */
@Composable
fun AppShell(
    library: LibraryRepository,
    catalog: CatalogRepository,
    downloads: DownloadManager,
    extensions: ExtensionRepo = NoOpExtensionRepo,
    challengeSolver: ChallengeSolver = NoOpChallengeSolver,
    theme: MangoTheme = MangoDark,
    onThemeChange: (MangoTheme) -> Unit = {},
    autoScrollSpeed: Float = 120f,
    onAutoScrollSpeedChange: (Float) -> Unit = {},
    onToggleFullscreen: () -> Unit = {},
    palette: PaletteState = remember { PaletteState() },
) {
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }
    // Reader has no fromBrowse of its own; remember which Details screen led to it so its
    // back button can return there.
    var lastDetails by remember { mutableStateOf<Screen.Details?>(null) }
    // Hoisted here (not inside BrowseScreen) so it survives tab switches: Library -> Browse ->
    // Library -> Browse must show the previous query/results instead of resetting.
    val browseState = remember { BrowseState() }
    // Same rationale as browseState: Search's query/results/enabled-sources must survive
    // switching to another tab and back.
    val searchState = remember { SearchState() }
    val detailsCache = remember { DetailsCache() }
    val scope = rememberCoroutineScope()

    // Both branches below live in a Box so PaletteOverlay can render on top of either one as a
    // full-screen layer, regardless of which screen is currently showing.
    Box(modifier = Modifier.fillMaxSize()) {
        when (val current = screen) {
            is Screen.Reader -> {
                ReaderScreen(
                    sourceId = current.sourceId,
                    mangaId = current.mangaId,
                    chapterId = current.chapterId,
                    chapters = current.chapters,
                    catalog = catalog,
                    downloads = downloads,
                    library = library,
                    challengeSolver = challengeSolver,
                    onBack = { lastDetails?.let { screen = it } ?: run { screen = Screen.Library } },
                    onToggleFullscreen = onToggleFullscreen,
                    autoScrollSpeedDpPerSec = autoScrollSpeed,
                    paletteVisible = palette.visible,
                )
            }
            else -> {
                Row(modifier = Modifier.fillMaxSize()) {
                    NavigationRail {
                        NavigationRailItem(
                            selected = current is Screen.Library,
                            onClick = { screen = Screen.Library },
                            icon = { Text("L") },
                            label = { Text("Library") },
                        )
                        NavigationRailItem(
                            selected = current is Screen.Search,
                            onClick = { screen = Screen.Search },
                            icon = { Text("⌕") },
                            label = { Text("Search") },
                        )
                        NavigationRailItem(
                            selected = current is Screen.Browse,
                            onClick = { screen = Screen.Browse },
                            icon = { Text("B") },
                            label = { Text("Browse") },
                        )
                        NavigationRailItem(
                            selected = current is Screen.Downloads,
                            onClick = { screen = Screen.Downloads },
                            icon = { Text("D") },
                            label = { Text("Downloads") },
                        )
                        NavigationRailItem(
                            selected = current is Screen.Extensions,
                            onClick = { screen = Screen.Extensions },
                            icon = { Text("E") },
                            label = { Text("Extensions") },
                        )
                        NavigationRailItem(
                            selected = current is Screen.Settings,
                            onClick = { screen = Screen.Settings },
                            icon = { Text("S") },
                            label = { Text("Settings") },
                        )
                    }
                    Surface(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        color = theme.bg0,
                    ) {
                        when (current) {
                            Screen.Library -> LibraryScreen(library) { entry ->
                                detailsCache.invalidate(entry.sourceId, entry.mangaId)
                                screen = Screen.Details(entry.sourceId, entry.mangaId, fromBrowse = false)
                            }
                            Screen.Search -> SearchScreen(catalog, challengeSolver, searchState) { entry ->
                                // Details has no fromSearch case yet: back from a Search-opened
                                // Details returns to Library, same as fromBrowse = false
                                // everywhere else that isn't Browse itself.
                                detailsCache.invalidate(entry.sourceId, entry.mangaId)
                                screen = Screen.Details(entry.sourceId, entry.mangaId, fromBrowse = false)
                            }
                            Screen.Browse -> BrowseScreen(catalog, challengeSolver, browseState) { entry ->
                                detailsCache.invalidate(entry.sourceId, entry.mangaId)
                                screen = Screen.Details(entry.sourceId, entry.mangaId, fromBrowse = true)
                            }
                            Screen.Downloads -> DownloadsScreen(downloads)
                            Screen.Extensions -> ExtensionsScreen(extensions, catalog)
                            Screen.Settings -> SettingsScreenContent(
                                theme = theme,
                                onThemeChange = onThemeChange,
                                autoScrollSpeed = autoScrollSpeed,
                                onAutoScrollSpeedChange = onAutoScrollSpeedChange,
                            )
                            is Screen.Details -> {
                                LaunchedEffect(current) { lastDetails = current }
                                Box(modifier = Modifier.fillMaxSize()) {
                                    DetailsScreen(
                                        sourceId = current.sourceId,
                                        mangaId = current.mangaId,
                                        catalog = catalog,
                                        library = library,
                                        downloads = downloads,
                                        challengeSolver = challengeSolver,
                                        cache = detailsCache,
                                        onOpenChapter = { chapter, chapters ->
                                            screen = Screen.Reader(
                                                sourceId = current.sourceId,
                                                mangaId = current.mangaId,
                                                chapterId = chapter.chapterId,
                                                chapters = chapters.sortedBy { it.number },
                                            )
                                        },
                                        // Downloading a chapter (or the whole series) implies the user
                                        // cares about it: it lands in the library too, same as a manual
                                        // "Add to library" tap.
                                        onDownloadChapter = { entry, chapter ->
                                            scope.launch {
                                                library.addToLibrary(entry)
                                                downloads.enqueue(entry, chapter)
                                                downloads.processQueue()
                                            }
                                        },
                                        onDownloadAll = { entry, chapters ->
                                            // empty selection ("unread" with everything read) must
                                            // not side-effect the library or spin the queue
                                            if (chapters.isNotEmpty()) {
                                                scope.launch {
                                                    library.addToLibrary(entry)
                                                    chapters.forEach { downloads.enqueue(entry, it) }
                                                    downloads.processQueue()
                                                }
                                            }
                                        },
                                    )
                                    IconButton(
                                        onClick = {
                                            screen = if (current.fromBrowse) Screen.Browse else Screen.Library
                                        },
                                        modifier = Modifier.padding(8.dp),
                                    ) {
                                        Text("←", color = theme.textSecondary)
                                    }
                                }
                            }
                            is Screen.Reader -> Unit // unreachable: handled in the branch above
                        }
                    }
                }
            }
        }
        // keyed on theme (not plain remember{}): the accent provider closes over the current
        // theme by value, so a theme change must rebuild the tab list or its hits would apply
        // an accent on top of a stale, already-replaced theme
        val tabs = remember(theme) {
            paletteTabs(
                library = library,
                navigate = { target ->
                    // a palette hit is a fresh open, same as a list tap: invalidate so the
                    // session cache can't serve stale details/chapters on this path
                    if (target is Screen.Details) detailsCache.invalidate(target.sourceId, target.mangaId)
                    screen = target
                },
                theme = theme,
                onThemeChange = onThemeChange,
            )
        }
        PaletteOverlay(state = palette, tabs = tabs)
    }
}
