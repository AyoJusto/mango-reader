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
import androidx.compose.material3.Button
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
import dev.mango.core.domain.ChallengeRequiredException
import dev.mango.core.domain.ChallengeSolver
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
    challengeUrl: String? = null,
    solving: Boolean = false,
    onSolveChallenge: () -> Unit = {},
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
                    error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (challengeUrl != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = onSolveChallenge, enabled = !solving) { Text("Solve challenge") }
                            if (solving) {
                                Text(
                                    text = "Opening browser… (first run downloads it, ~100MB)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    results.isEmpty() -> Text(
                        text = "No results",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // no item keys: extension data is untrusted and a duplicate mangaId in one
                    // response must not crash composition with a duplicate-key exception
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        contentPadding = PaddingValues(0.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(results) { entry ->
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
 * query, results, or selection. The source list itself reloads on each entry so fresh installs
 * from the Extensions tab appear immediately.
 */
class BrowseState {
    var sources by mutableStateOf<List<SourceInfo>>(emptyList())
    var selectedSourceId by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var isLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var challengeUrl by mutableStateOf<String?>(null)
    var solving by mutableStateOf(false)
    var results by mutableStateOf<List<MangaEntry>>(emptyList())
}

/** Stateful loader: one-shot source list, then search-on-submit against [CatalogRepository]. */
@Composable
fun BrowseScreen(
    catalog: CatalogRepository,
    challengeSolver: ChallengeSolver,
    state: BrowseState,
    onOpenDetails: (MangaEntry) -> Unit,
) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // Reload on every entry, not one-shot: an extension installed on the Extensions tab
        // must show up here without restarting the app. Query/results retention is untouched.
        state.sources = catalog.installedSources()
        if (state.sources.none { it.sourceId == state.selectedSourceId }) {
            state.selectedSourceId = state.sources.firstOrNull()?.sourceId
        }
    }

    fun search() {
        val sourceId = state.selectedSourceId ?: return
        scope.launch {
            state.isLoading = true
            state.error = null
            state.challengeUrl = null
            try {
                state.results = catalog.search(sourceId, state.query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChallengeRequiredException) {
                state.error = "This source is protected by Cloudflare"
                state.challengeUrl = e.url
            } catch (e: Exception) {
                state.error = e.message ?: "Search failed"
            } finally {
                state.isLoading = false
            }
        }
    }

    fun solveChallenge() {
        val sourceId = state.selectedSourceId ?: return
        val url = state.challengeUrl ?: return
        scope.launch {
            state.solving = true
            try {
                if (challengeSolver.solve(sourceId, url)) search()
            } finally {
                state.solving = false
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
        challengeUrl = state.challengeUrl,
        solving = state.solving,
        onSolveChallenge = { solveChallenge() },
        results = state.results,
        onOpenDetails = onOpenDetails,
    )
}
