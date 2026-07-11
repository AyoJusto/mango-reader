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
import androidx.compose.runtime.remember
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

/** Stateful loader: one-shot source list, then search-on-submit against [CatalogRepository]. */
@Composable
fun BrowseScreen(catalog: CatalogRepository, onOpenDetails: (MangaEntry) -> Unit) {
    var sources by remember { mutableStateOf<List<SourceInfo>>(emptyList()) }
    var selectedSourceId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<MangaEntry>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        sources = catalog.installedSources()
        selectedSourceId = sources.firstOrNull()?.sourceId
    }

    fun search() {
        val sourceId = selectedSourceId ?: return
        scope.launch {
            isLoading = true
            error = null
            try {
                results = catalog.search(sourceId, query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Search failed"
            } finally {
                isLoading = false
            }
        }
    }

    BrowseScreenContent(
        sources = sources,
        selectedSourceId = selectedSourceId,
        onSelectSource = { selectedSourceId = it },
        query = query,
        onQueryChange = { query = it },
        onSearch = { search() },
        isLoading = isLoading,
        error = error,
        results = results,
        onOpenDetails = onOpenDetails,
    )
}
