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
import androidx.compose.foundation.layout.width
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.logging.Level
import java.util.logging.Logger

/** One installed source's results/error within the search screen's per-source section. */
@Composable
private fun SearchSourceSection(
    source: SourceInfo,
    results: List<MangaEntry>,
    error: String?,
    challengeUrl: String?,
    // pending = this source's search is still in flight (others render without waiting)
    pending: Boolean,
    // solving = THIS source's solve is running; solveEnabled = no solve is running anywhere
    // (one embedded browser at a time, but only the solving source shows the progress hint)
    solving: Boolean,
    solveEnabled: Boolean,
    onOpenDetails: (MangaEntry) -> Unit,
    onSolveChallenge: () -> Unit,
) {
    val theme = LocalMangoTheme.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = source.name, style = MaterialTheme.typography.titleMedium)
            if (pending) {
                CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
            } else if (error != null) {
                Text(text = error, style = MaterialTheme.typography.bodyMedium, color = theme.danger)
                if (challengeUrl != null) {
                    Button(onClick = onSolveChallenge, enabled = solveEnabled) { Text("Solve challenge") }
                }
            } else {
                Text(
                    text = "${results.size} result${if (results.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.textSecondary,
                )
            }
        }
        if (solving && challengeUrl != null) {
            SolveProgressHint()
        }
        Spacer(modifier = Modifier.height(8.dp))
        when {
            pending -> Unit // spinner already shown in the header row above
            error != null -> Unit // error already surfaced in the header row above
            results.isEmpty() -> Text(
                text = "No results",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.textSecondary,
            )
            // no item keys: extension data is untrusted and a duplicate mangaId in one
            // response must not crash composition with a duplicate-key exception
            else -> LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().height(280.dp),
            ) {
                items(results) { entry ->
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
    pendingSourceIds: Set<String>,
    resultsBySource: Map<String, List<MangaEntry>>,
    errorsBySource: Map<String, String>,
    challengeUrlsBySource: Map<String, String>,
    onOpenDetails: (MangaEntry) -> Unit,
    solvingSourceId: String? = null,
    onSolveChallenge: (String) -> Unit = {},
) {
    val theme = LocalMangoTheme.current
    Surface(modifier = Modifier.fillMaxSize(), color = theme.bg0) {
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
            // no per-source data and nothing in flight means no search has run yet: show an
            // idle hint instead of a "No results" section per source. There is deliberately
            // no whole-screen spinner: each source's section appears the moment that source
            // answers, so one slow extension never blocks the others' results.
            val hasSearched =
                resultsBySource.isNotEmpty() || errorsBySource.isNotEmpty() || pendingSourceIds.isNotEmpty()
            if (!hasSearched) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (sources.isNotEmpty() && enabledSourceIds.isEmpty()) {
                            "No sources selected"
                        } else {
                            "Search across all installed sources"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.textSecondary,
                    )
                }
            } else {
                // sections reflect what was actually searched (the result/error maps), not the
                // live chip state: toggling a chip after a search must neither fabricate a
                // false "No results" section nor hide results already fetched
                val searchedSources = sources.filter {
                    it.sourceId in resultsBySource || it.sourceId in errorsBySource ||
                        it.sourceId in pendingSourceIds
                }
                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(searchedSources, key = { it.sourceId }) { source ->
                        SearchSourceSection(
                            source = source,
                            results = resultsBySource[source.sourceId] ?: emptyList(),
                            error = errorsBySource[source.sourceId],
                            challengeUrl = challengeUrlsBySource[source.sourceId],
                            pending = source.sourceId in pendingSourceIds,
                            solving = solvingSourceId == source.sourceId,
                            solveEnabled = solvingSourceId == null,
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
    var pendingSourceIds by mutableStateOf<Set<String>>(emptySet())
    var results by mutableStateOf<Map<String, List<MangaEntry>>>(emptyMap())
    var errors by mutableStateOf<Map<String, String>>(emptyMap())
    var challengeUrls by mutableStateOf<Map<String, String>>(emptyMap())
    var solvingSourceId by mutableStateOf<String?>(null)

    // last fan-out, cancelled when a new search is submitted so overlapping runs can't
    // interleave stale results into the new query's maps; plain field, not UI state
    var searchJob: Job? = null
}

/**
 * Stateful loader: source list reloaded on entry, then a fan-out search-on-submit across every enabled
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
        try {
            val previousIds = state.sources.map { it.sourceId }.toSet()
            val loaded = catalog.installedSources()
            val loadedIds = loaded.map { it.sourceId }.toSet()
            val newlyAppeared = loadedIds - previousIds
            state.enabledSourceIds = (state.enabledSourceIds intersect loadedIds) + newlyAppeared
            state.sources = loaded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a failed registry read keeps the previous (possibly empty) list; crashing the
            // whole app out of a LaunchedEffect over a local DB hiccup would be worse
            Logger.getLogger("SearchScreen").log(Level.WARNING, "failed to load installed sources", e)
        }
    }

    // One source's search, isolated so its failure never affects the others; on success any
    // stale error/challenge for that source is cleared (the solve flow re-runs just this).
    // Marks itself no-longer-pending when it settles, so its section renders immediately
    // instead of waiting for the slowest source in the fan-out.
    suspend fun searchOne(sourceId: String, query: String) {
        try {
            val found = catalog.search(sourceId, query)
            state.results = state.results + (sourceId to found)
            state.errors = state.errors - sourceId
            state.challengeUrls = state.challengeUrls - sourceId
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChallengeRequiredException) {
            state.errors = state.errors + (sourceId to "Protected by Cloudflare")
            e.url?.let { url -> state.challengeUrls = state.challengeUrls + (sourceId to url) }
        } catch (e: Exception) {
            state.errors = state.errors + (sourceId to (e.message ?: "Search failed"))
        } finally {
            state.pendingSourceIds = state.pendingSourceIds - sourceId
        }
    }

    fun search() {
        val enabled = state.enabledSourceIds
        val query = state.query
        // nothing enabled: the idle hint already reads "No sources selected"
        if (enabled.isEmpty()) return
        state.searchJob?.cancel()
        state.searchJob = scope.launch {
            state.pendingSourceIds = enabled
            state.results = emptyMap()
            state.errors = emptyMap()
            state.challengeUrls = emptyMap()
            try {
                coroutineScope {
                    enabled.map { sourceId -> async { searchOne(sourceId, query) } }
                }
            } finally {
                // finally also runs on cancellation (tab switch mid-search), so the
                // shell-hoisted state can never get stuck showing per-source spinners
                state.pendingSourceIds = emptySet()
            }
        }
    }

    fun solveChallenge(sourceId: String) {
        val url = state.challengeUrls[sourceId] ?: return
        val query = state.query
        scope.launch {
            state.solvingSourceId = sourceId
            try {
                // refetch only the solved source: re-running the whole fan-out would hit the
                // other sources again for nothing (polite-scraper rule)
                if (challengeSolver.solve(sourceId, url)) searchOne(sourceId, query)
            } finally {
                state.solvingSourceId = null
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
        pendingSourceIds = state.pendingSourceIds,
        resultsBySource = state.results,
        errorsBySource = state.errors,
        challengeUrlsBySource = state.challengeUrls,
        onOpenDetails = onOpenDetails,
        solvingSourceId = state.solvingSourceId,
        onSolveChallenge = { sourceId -> solveChallenge(sourceId) },
    )
}
