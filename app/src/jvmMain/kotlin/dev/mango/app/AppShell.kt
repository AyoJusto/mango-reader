package dev.mango.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
 * Navigation state for the app shell. Hand-rolled — no nav library (M3.2, PLANNING §13
 * simplicity bias): the tree is four flat cases, not worth a dependency.
 */
sealed interface Screen {
    data object Library : Screen
    data object Browse : Screen
    data object Downloads : Screen
    data object Extensions : Screen
    data object Settings : Screen
    data class Details(val sourceId: String, val mangaId: String, val fromBrowse: Boolean) : Screen
    data class Reader(
        val sourceId: String,
        val mangaId: String,
        val chapterId: String,
        // display metadata rides along: chapterId is an opaque source token
        // (e.g. "143:55fa2d51e489132b"), never something a reader should see
        val chapterNumber: Double,
        val chapterTitle: String?,
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
    currentTheme: String = Themes.DEFAULT,
    onThemeChange: (String) -> Unit = {},
    onToggleFullscreen: () -> Unit = {},
) {
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }
    // Reader has no fromBrowse of its own; remember which Details screen led to it so its
    // back button can return there.
    var lastDetails by remember { mutableStateOf<Screen.Details?>(null) }
    // Hoisted here (not inside BrowseScreen) so it survives tab switches: Library -> Browse ->
    // Library -> Browse must show the previous query/results instead of resetting.
    val browseState = remember { BrowseState() }
    val scope = rememberCoroutineScope()

    when (val current = screen) {
        is Screen.Reader -> {
            ReaderScreen(
                sourceId = current.sourceId,
                mangaId = current.mangaId,
                chapterId = current.chapterId,
                chapterLabel = buildString {
                    append("Ch. ")
                    append(formatChapterNumber(current.chapterNumber))
                    current.chapterTitle?.let { append(" — "); append(it) }
                },
                catalog = catalog,
                downloads = downloads,
                library = library,
                challengeSolver = challengeSolver,
                onBack = { lastDetails?.let { screen = it } ?: run { screen = Screen.Library } },
                onToggleFullscreen = onToggleFullscreen,
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
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (current) {
                        Screen.Library -> LibraryScreen(library) { entry ->
                            screen = Screen.Details(entry.sourceId, entry.mangaId, fromBrowse = false)
                        }
                        Screen.Browse -> BrowseScreen(catalog, challengeSolver, browseState) { entry ->
                            screen = Screen.Details(entry.sourceId, entry.mangaId, fromBrowse = true)
                        }
                        Screen.Downloads -> DownloadsScreen(downloads)
                        Screen.Extensions -> ExtensionsScreen(extensions, catalog)
                        Screen.Settings -> SettingsScreenContent(
                            themeNames = Themes.schemes.keys.toList(),
                            currentTheme = currentTheme,
                            onSelectTheme = onThemeChange,
                        )
                        is Screen.Details -> {
                            LaunchedEffect(current) { lastDetails = current }
                            Box(modifier = Modifier.fillMaxSize()) {
                                DetailsScreen(
                                    sourceId = current.sourceId,
                                    mangaId = current.mangaId,
                                    catalog = catalog,
                                    library = library,
                                    challengeSolver = challengeSolver,
                                    onOpenChapter = { chapter ->
                                        screen = Screen.Reader(
                                            sourceId = current.sourceId,
                                            mangaId = current.mangaId,
                                            chapterId = chapter.chapterId,
                                            chapterNumber = chapter.number,
                                            chapterTitle = chapter.title,
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
                                        scope.launch {
                                            library.addToLibrary(entry)
                                            chapters.forEach { downloads.enqueue(entry, it) }
                                            downloads.processQueue()
                                        }
                                    },
                                )
                                IconButton(
                                    onClick = {
                                        screen = if (current.fromBrowse) Screen.Browse else Screen.Library
                                    },
                                    modifier = Modifier.padding(8.dp),
                                ) {
                                    Text("←", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        is Screen.Reader -> Unit // unreachable: handled in the branch above
                    }
                }
            }
        }
    }
}
