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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.LibraryRepository

/**
 * Navigation state for the app shell. Hand-rolled — no nav library (M3.2, PLANNING §13
 * simplicity bias): the tree is four flat cases, not worth a dependency.
 */
sealed interface Screen {
    data object Library : Screen
    data object Browse : Screen
    data class Details(val sourceId: String, val mangaId: String, val fromBrowse: Boolean) : Screen
    data class Reader(val sourceId: String, val mangaId: String, val chapterId: String) : Screen
}

/**
 * The whole application UI: a left rail for the two top-level tabs (Library, Browse) plus
 * drill-down screens (Details, Reader) that replace the content area. Screens talk only to
 * the repository ports passed in here — never engine or DB types (CLAUDE.md boundary).
 */
@Composable
fun AppShell(library: LibraryRepository, catalog: CatalogRepository) {
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }
    // Reader has no fromBrowse of its own; remember which Details screen led to it so its
    // back button can return there.
    var lastDetails by remember { mutableStateOf<Screen.Details?>(null) }

    when (val current = screen) {
        is Screen.Reader -> {
            Surface(modifier = Modifier.fillMaxSize(), color = ReaderBlack) {
                Box(modifier = Modifier.fillMaxSize()) {
                    IconButton(
                        onClick = { lastDetails?.let { screen = it } ?: run { screen = Screen.Library } },
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Text("←", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        text = "Reader arrives in M3.3",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
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
                }
                Surface(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (current) {
                        Screen.Library -> LibraryScreen(library) { entry ->
                            screen = Screen.Details(entry.sourceId, entry.mangaId, fromBrowse = false)
                        }
                        Screen.Browse -> BrowseScreen(catalog) { entry ->
                            screen = Screen.Details(entry.sourceId, entry.mangaId, fromBrowse = true)
                        }
                        is Screen.Details -> {
                            LaunchedEffect(current) { lastDetails = current }
                            Box(modifier = Modifier.fillMaxSize()) {
                                DetailsScreen(
                                    sourceId = current.sourceId,
                                    mangaId = current.mangaId,
                                    catalog = catalog,
                                    library = library,
                                    onOpenChapter = { chapter ->
                                        screen = Screen.Reader(current.sourceId, current.mangaId, chapter.chapterId)
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
