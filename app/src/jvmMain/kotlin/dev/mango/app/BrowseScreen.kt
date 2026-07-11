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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import dev.mango.core.domain.HomeSection
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.SourceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.logging.Level
import java.util.logging.Logger

/** Error text (+ optional Solve button) shared by Browse's search-mode and sections-mode content. */
@Composable
private fun ChallengeErrorContent(
    error: String,
    challengeUrl: String?,
    solving: Boolean,
    solveEnabled: Boolean,
    onSolveChallenge: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        if (challengeUrl != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onSolveChallenge, enabled = solveEnabled) { Text("Solve challenge") }
            if (solving) {
                Text(
                    text = "Opening browser… (first run downloads it, ~100MB)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One discover/home section: title + a horizontal shelf of covers, same look as Search's per-source row. */
@Composable
private fun BrowseSectionRow(section: HomeSection, onOpenDetails: (MangaEntry) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = section.title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        // no item keys: extension data is untrusted and a duplicate mangaId in one
        // response must not crash composition with a duplicate-key exception
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().height(280.dp),
        ) {
            items(section.items) { entry ->
                CoverCell(entry = entry, onClick = { onOpenDetails(entry) }, modifier = Modifier.height(280.dp))
            }
        }
    }
}

/** Pure, data-driven content — the screenshot harness renders this directly. */
@Composable
fun BrowseScreenContent(
    sources: List<SourceInfo>,
    selectedSourceId: String?,
    onSelectSource: (String) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    searchActive: Boolean,
    isLoading: Boolean,
    error: String?,
    results: List<MangaEntry>,
    sections: List<HomeSection>,
    sectionsLoading: Boolean,
    sectionsError: String?,
    onOpenDetails: (MangaEntry) -> Unit,
    sourcesError: String? = null,
    challengeUrl: String? = null,
    solvingSourceId: String? = null,
    onSolveChallenge: () -> Unit = {},
) {
    // one solve at a time within this screen: the button is disabled while ANY source is
    // solving, but the progress hint only shows on this source (see BrowseState.solvingSourceId)
    val solving = solvingSourceId != null && solvingSourceId == selectedSourceId
    val solveEnabled = solvingSourceId == null

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
                if (selectedSourceId == null) {
                    // nothing to browse: either the registry read failed (say so honestly) or
                    // there are genuinely no sources — never the sections-mode hint
                    Text(
                        text = sourcesError ?: "No sources installed — add one from the Extensions tab",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (sourcesError != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                } else if (searchActive) {
                    when {
                        isLoading -> CircularProgressIndicator()
                        error != null -> ChallengeErrorContent(
                            error = error,
                            challengeUrl = challengeUrl,
                            solving = solving,
                            solveEnabled = solveEnabled,
                            onSolveChallenge = onSolveChallenge,
                        )
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
                } else {
                    when {
                        sectionsLoading -> CircularProgressIndicator()
                        sectionsError != null -> ChallengeErrorContent(
                            error = sectionsError,
                            challengeUrl = challengeUrl,
                            solving = solving,
                            solveEnabled = solveEnabled,
                            onSolveChallenge = onSolveChallenge,
                        )
                        sections.isNotEmpty() -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            // no item keys: extension-provided section ids are untrusted and a
                            // duplicate must not crash composition with a duplicate-key exception
                            items(sections) { section ->
                                BrowseSectionRow(section = section, onOpenDetails = onOpenDetails)
                            }
                        }
                        else -> Text(
                            text = "No discover sections — search this source instead",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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

    // why the last installed-sources reload failed; shown only when there is nothing to select
    var sourcesError by mutableStateOf<String?>(null)

    // discover/home sections, cached per source for the lifetime of this state (session cache):
    // a tab revisit after a successful load never re-fetches. Errors are NOT cached as terminal —
    // see fetchSections below — so a revisit after a failure retries.
    var sectionsBySource by mutableStateOf<Map<String, List<HomeSection>>>(emptyMap())
    var sectionsPending by mutableStateOf<Set<String>>(emptySet())
    var sectionsErrors by mutableStateOf<Map<String, String>>(emptyMap())
    // challenge urls are split by mode (this map vs searchChallengeUrl below): a stale search
    // challenge must never render a Solve button on a sections error, and vice versa
    var sectionsChallengeUrls by mutableStateOf<Map<String, String>>(emptyMap())

    // one solve at a time within this screen; the app-wide one-embedded-browser invariant
    // currently holds because single-pane navigation composes one screen at a time — hoisting
    // a real gate into the solver is a recorded ceiling (PLANNING)
    var solvingSourceId by mutableStateOf<String?>(null)

    // search is single-source-at-a-time here (unlike Search tab's fan-out), so a plain
    // results/error pair is enough; searchedSourceId says which source they belong to so
    // switching chips can tell "these are stale results for another source" from "these are S's".
    var results by mutableStateOf<List<MangaEntry>>(emptyList())
    var isLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var searchChallengeUrl by mutableStateOf<String?>(null)
    var searchedSourceId by mutableStateOf<String?>(null)

    // the query the current results/error came from: the post-solve rerun uses this, never the
    // live text field (the user may have typed since); plain field, never rendered
    var searchedQuery: String = ""

    // last search, cancelled when a new one is submitted so a slow older search can't
    // overwrite a newer one's results/isLoading/error; plain field, not UI state
    var searchJob: Job? = null
}

/**
 * Stateful loader: one-shot-per-entry source list, lazy per-tab discover sections, and
 * search-on-submit against [CatalogRepository].
 */
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
        try {
            state.sources = catalog.installedSources()
            state.sourcesError = null
            if (state.sources.none { it.sourceId == state.selectedSourceId }) {
                state.selectedSourceId = state.sources.firstOrNull()?.sourceId
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a failed registry read keeps the previous (possibly empty) list; crashing the
            // whole app out of a LaunchedEffect over a local DB hiccup would be worse
            state.sourcesError = e.message ?: "Failed to load sources"
            Logger.getLogger("BrowseScreen").log(Level.WARNING, "failed to load installed sources", e)
        }
    }

    // One source's discover sections, isolated so a retry (tab revisit, or a challenge solve)
    // never disturbs another source's cache entry. Clears its own stale error AND challenge url
    // at start: a later non-challenge failure must not render a Solve button for an old URL.
    suspend fun fetchSections(sourceId: String) {
        state.sectionsPending = state.sectionsPending + sourceId
        state.sectionsErrors = state.sectionsErrors - sourceId
        state.sectionsChallengeUrls = state.sectionsChallengeUrls - sourceId
        try {
            val loaded = catalog.homeSections(sourceId)
            state.sectionsBySource = state.sectionsBySource + (sourceId to loaded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChallengeRequiredException) {
            state.sectionsErrors = state.sectionsErrors + (sourceId to "Protected by Cloudflare")
            e.url?.let { url -> state.sectionsChallengeUrls = state.sectionsChallengeUrls + (sourceId to url) }
        } catch (e: Exception) {
            state.sectionsErrors = state.sectionsErrors + (sourceId to (e.message ?: "Failed to load"))
        } finally {
            state.sectionsPending = state.sectionsPending - sourceId
        }
    }

    // the composition-captured selection, also the sections effect's key: the effect must act
    // on the value it was keyed with, never a live state read — the entry effect can set the
    // selection between composition and the null-keyed run executing, which would make that
    // run fetch AND the keyed restart fetch again (an error entry is retryable by design)
    val selected = state.selectedSourceId

    LaunchedEffect(selected) {
        // Selected tab only, on demand: never prefetch other sources' sections (polite-scraper
        // rule). A cached success is never re-fetched; a cached error is retried on revisit.
        val sourceId = selected
        if (sourceId != null && sourceId !in state.sectionsBySource && sourceId !in state.sectionsPending) {
            // launched into the screen scope, not this effect's own coroutine: a chip flip must
            // not cancel an in-flight load (it completes into the cache — that's a requested
            // fetch finishing, not a prefetch), and the pending-guard can no longer race a
            // cancelled effect's finally block
            scope.launch { fetchSections(sourceId) }
        }
    }

    // Query is a parameter (like SearchScreen.searchOne), captured at submit time: the user may
    // edit the text field while this is in flight, and the post-solve rerun must use what was
    // actually searched. Touches ONLY search-mode challenge state, never the sections map.
    suspend fun searchOnce(sourceId: String, query: String) {
        state.isLoading = true
        state.error = null
        state.searchChallengeUrl = null
        try {
            state.results = catalog.search(sourceId, query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChallengeRequiredException) {
            state.error = "This source is protected by Cloudflare"
            state.searchChallengeUrl = e.url
        } catch (e: Exception) {
            state.error = e.message ?: "Search failed"
        } finally {
            state.isLoading = false
        }
    }

    fun search() {
        val sourceId = state.selectedSourceId ?: return
        if (state.query.isBlank()) {
            // Deliberate v1 way back to sections mode: there is no explicit clear button, so
            // submitting a blank query is how a tab returns to showing discover sections.
            state.searchJob?.cancel()
            state.results = emptyList()
            state.error = null
            state.searchedSourceId = null
            return
        }
        val query = state.query
        state.searchedSourceId = sourceId
        state.searchedQuery = query
        // cancel any in-flight search first: a slow older search must not overwrite the
        // results/isLoading/error trio after this newer one has rendered
        state.searchJob?.cancel()
        state.searchJob = scope.launch { searchOnce(sourceId, query) }
    }

    // fromSearchMode is captured at click time (the Solve button only renders in the active
    // mode), so the post-solve rerun targets the mode the user actually saw — never re-read
    // from searchedSourceId after the solve returns.
    fun solveChallenge(sourceId: String, fromSearchMode: Boolean) {
        val url = (if (fromSearchMode) state.searchChallengeUrl else state.sectionsChallengeUrls[sourceId])
            ?: return
        scope.launch {
            state.solvingSourceId = sourceId
            try {
                if (challengeSolver.solve(sourceId, url)) {
                    // polite-scraper rule: rerun only this source's fetch in the solved mode
                    if (fromSearchMode) searchOnce(sourceId, state.searchedQuery) else fetchSections(sourceId)
                }
            } finally {
                state.solvingSourceId = null
            }
        }
    }

    val searchActive = selected != null && state.searchedSourceId == selected

    BrowseScreenContent(
        sources = state.sources,
        selectedSourceId = selected,
        onSelectSource = { state.selectedSourceId = it },
        query = state.query,
        onQueryChange = { state.query = it },
        onSearch = { search() },
        searchActive = searchActive,
        isLoading = state.isLoading,
        error = state.error,
        results = state.results,
        sections = selected?.let { state.sectionsBySource[it] } ?: emptyList(),
        sectionsLoading = selected != null && selected in state.sectionsPending,
        sectionsError = selected?.let { state.sectionsErrors[it] },
        onOpenDetails = onOpenDetails,
        sourcesError = state.sourcesError,
        challengeUrl = if (searchActive) {
            state.searchChallengeUrl
        } else {
            selected?.let { state.sectionsChallengeUrls[it] }
        },
        solvingSourceId = state.solvingSourceId,
        onSolveChallenge = { selected?.let { solveChallenge(it, searchActive) } },
    )
}
