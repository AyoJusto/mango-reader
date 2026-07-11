package dev.mango.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.SourceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Pure, data-driven content — the screenshot harness renders this directly. */
@Composable
fun BrowseScreenContent(
    sources: List<SourceInfo>,
    selectedSourceId: String?,
    onSelectSource: (String) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    isLoading: Boolean,
    error: String?,
    results: List<MangaEntry>,
    onOpenDetails: (MangaEntry) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sources.forEach { source ->
                    FilterChip(
                        selected = source.sourceId == selectedSourceId,
                        onClick = { onSelectSource(source.sourceId) },
                        label = { Text(source.name) },
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Search…") },
                    leadingIcon = { Text("⌕") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    isLoading -> CircularProgressIndicator()
                    error != null -> Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    results.isEmpty() -> Text(
                        text = "No results",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        contentPadding = PaddingValues(0.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(results, key = { "${it.sourceId}/${it.mangaId}" }) { entry ->
                            CoverCell(entry = entry, onClick = { onOpenDetails(entry) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Browse's screen state, hoisted out of [BrowseScreen] so the caller can [remember] it at the
 * shell level (see [AppShell]): switching tabs away from and back to Browse must not lose the
 * query, results, or re-trigger the one-shot sources load.
 */
class BrowseState {
    var sources by mutableStateOf<List<SourceInfo>>(emptyList())
    var sourcesLoaded by mutableStateOf(false)
    var selectedSourceId by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var isLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var results by mutableStateOf<List<MangaEntry>>(emptyList())
}

/** Stateful loader: one-shot source list, then search-on-submit against [CatalogRepository]. */
@Composable
fun BrowseScreen(catalog: CatalogRepository, state: BrowseState, onOpenDetails: (MangaEntry) -> Unit) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (!state.sourcesLoaded) {
            state.sources = catalog.installedSources()
            state.selectedSourceId = state.sources.firstOrNull()?.sourceId
            state.sourcesLoaded = true
        }
    }

    fun search() {
        val sourceId = state.selectedSourceId ?: return
        scope.launch {
            state.isLoading = true
            state.error = null
            try {
                state.results = catalog.search(sourceId, state.query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.error = e.message ?: "Search failed"
            } finally {
                state.isLoading = false
            }
        }
    }

    BrowseScreenContent(
        sources = state.sources,
        selectedSourceId = state.selectedSourceId,
        onSelectSource = { state.selectedSourceId = it },
        query = state.query,
        onQueryChange = { state.query = it },
        onSearch = { search() },
        isLoading = state.isLoading,
        error = state.error,
        results = state.results,
        onOpenDetails = onOpenDetails,
    )
}
