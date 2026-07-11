package dev.mango.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** One installed source's results/error within the search screen's per-source section. */
@Composable
private fun SearchSourceSection(
    source: SourceInfo,
    results: List<MangaEntry>,
    error: String?,
    challengeUrl: String?,
    solving: Boolean,
    onOpenDetails: (MangaEntry) -> Unit,
    onSolveChallenge: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = source.name, style = MaterialTheme.typography.titleMedium)
            if (error != null) {
                Text(text = error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                if (challengeUrl != null) {
                    Button(onClick = onSolveChallenge, enabled = !solving) { Text("Solve challenge") }
                }
            } else {
                Text(
                    text = "${results.size} result${if (results.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (solving && challengeUrl != null) {
            Text(
                text = "Opening browser… (first run downloads it, ~100MB)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        when {
            error != null -> Unit // error already surfaced in the header row above
            results.isEmpty() -> Text(
                text = "No results",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().height(280.dp),
            ) {
                items(results, key = { "${it.sourceId}/${it.mangaId}" }) { entry ->
                    CoverCell(entry = entry, onClick = { onOpenDetails(entry) }, modifier = Modifier.height(280.dp))
                }
            }
        }
    }
}

/** Pure, data-driven content — the screenshot harness renders this directly. */
@Composable
fun SearchScreenContent(
    sources: List<SourceInfo>,
    enabledSourceIds: Set<String>,
    onToggleSource: (String) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    isLoading: Boolean,
    resultsBySource: Map<String, List<MangaEntry>>,
    errorsBySource: Map<String, String>,
    challengeUrlsBySource: Map<String, String>,
    onOpenDetails: (MangaEntry) -> Unit,
    solving: Boolean = false,
    onSolveChallenge: (String) -> Unit = {},
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search all sources…") },
                leadingIcon = { Text("⌕") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sources.forEach { source ->
                    FilterChip(
                        selected = source.sourceId in enabledSourceIds,
                        onClick = { onToggleSource(source.sourceId) },
                        label = { Text(source.name) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            // no per-source data at all means no search has run yet: show an idle hint
            // instead of a "No results" section per source
            val hasSearched = resultsBySource.isNotEmpty() || errorsBySource.isNotEmpty()
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (!hasSearched) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Search across all installed sources",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val enabledSources = sources.filter { it.sourceId in enabledSourceIds }
                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(enabledSources, key = { it.sourceId }) { source ->
                        SearchSourceSection(
                            source = source,
                            results = resultsBySource[source.sourceId] ?: emptyList(),
                            error = errorsBySource[source.sourceId],
                            challengeUrl = challengeUrlsBySource[source.sourceId],
                            solving = solving,
                            onOpenDetails = onOpenDetails,
                            onSolveChallenge = { onSolveChallenge(source.sourceId) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Search's screen state, hoisted out of [SearchScreen] so the caller can [remember] it at the
 * shell level (see [AppShell]): switching tabs away from and back to Search must not lose the
 * query, results, or which sources are enabled. The source list itself reloads on each entry
 * (see [SearchScreen]'s LaunchedEffect) so fresh installs from the Extensions tab appear.
 */
class SearchState {
    var sources by mutableStateOf<List<SourceInfo>>(emptyList())
    var enabledSourceIds by mutableStateOf<Set<String>>(emptySet())
    var query by mutableStateOf("")
    var isLoading by mutableStateOf(false)
    var results by mutableStateOf<Map<String, List<MangaEntry>>>(emptyMap())
    var errors by mutableStateOf<Map<String, String>>(emptyMap())
    var challengeUrls by mutableStateOf<Map<String, String>>(emptyMap())
    var solving by mutableStateOf(false)
}

/**
 * Stateful loader: one-shot source list, then a fan-out search-on-submit across every enabled
 * source in parallel. Each source's search is isolated in its own try/catch so one source
 * failing (or being Cloudflare-gated) never hides another source's results.
 */
@Composable
fun SearchScreen(
    catalog: CatalogRepository,
    challengeSolver: ChallengeSolver,
    state: SearchState,
    onOpenDetails: (MangaEntry) -> Unit,
) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // Reload on every entry, not one-shot: an extension installed on the Extensions tab
        // must show up here without restarting the app. Newly appearing sources default to
        // enabled; sources the user already disabled stay disabled.
        val previousIds = state.sources.map { it.sourceId }.toSet()
        val loaded = catalog.installedSources()
        val loadedIds = loaded.map { it.sourceId }.toSet()
        val newlyAppeared = loadedIds - previousIds
        state.enabledSourceIds = (state.enabledSourceIds intersect loadedIds) + newlyAppeared
        state.sources = loaded
    }

    fun search() {
        val enabled = state.enabledSourceIds
        val query = state.query
        scope.launch {
            state.isLoading = true
            state.results = emptyMap()
            state.errors = emptyMap()
            state.challengeUrls = emptyMap()
            coroutineScope {
                enabled.map { sourceId ->
                    async {
                        try {
                            val found = catalog.search(sourceId, query)
                            state.results = state.results + (sourceId to found)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: ChallengeRequiredException) {
                            state.errors = state.errors + (sourceId to "Protected by Cloudflare")
                            e.url?.let { url -> state.challengeUrls = state.challengeUrls + (sourceId to url) }
                        } catch (e: Exception) {
                            state.errors = state.errors + (sourceId to (e.message ?: "Search failed"))
                        }
                    }
                }
            }
            state.isLoading = false
        }
    }

    fun solveChallenge(sourceId: String) {
        val url = state.challengeUrls[sourceId] ?: return
        scope.launch {
            state.solving = true
            try {
                if (challengeSolver.solve(sourceId, url)) search()
            } finally {
                state.solving = false
            }
        }
    }

    SearchScreenContent(
        sources = state.sources,
        enabledSourceIds = state.enabledSourceIds,
        onToggleSource = { sourceId ->
            state.enabledSourceIds = if (sourceId in state.enabledSourceIds) {
                state.enabledSourceIds - sourceId
            } else {
                state.enabledSourceIds + sourceId
            }
        },
        query = state.query,
        onQueryChange = { state.query = it },
        onSearch = { search() },
        isLoading = state.isLoading,
        resultsBySource = state.results,
        errorsBySource = state.errors,
        challengeUrlsBySource = state.challengeUrls,
        onOpenDetails = onOpenDetails,
        solving = state.solving,
        onSolveChallenge = { sourceId -> solveChallenge(sourceId) },
    )
}
