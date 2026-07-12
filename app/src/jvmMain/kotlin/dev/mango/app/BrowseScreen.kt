package dev.mango.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
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

/** Cover width used by both the search-results grid and the discover shelves: board 07's "5-column feel". */
private val BROWSE_COVER_WIDTH = 190.dp

/** Board 07's search-input grammar, shared by every text query field on this screen. */
private val SEARCH_FIELD_RADIUS = MangoRadius.row
private val SEARCH_FIELD_RING_RADIUS = SEARCH_FIELD_RADIUS + 2.dp

/**
 * The one styled text query field on Browse: bg2 fill, radius 12, a focus ring offset from the
 * control by a bg gap, and an accent caret. Kept file-private and duplicated in SearchScreen.kt
 * rather than added to Kit.kt (out of scope for this restyle).
 */
@Composable
private fun StyledSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalMangoTheme.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val ring = if (focused) {
        Modifier.border(2.dp, theme.focus, RoundedCornerShape(SEARCH_FIELD_RING_RADIUS))
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .then(ring)
            .padding(2.dp)
            .clip(RoundedCornerShape(SEARCH_FIELD_RADIUS))
            .background(theme.bg2)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm)) {
            Text(text = "⌕", style = MangoType.body, color = theme.textTertiary)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                textStyle = MangoType.body.copy(color = theme.textPrimary),
                singleLine = true,
                cursorBrush = SolidColor(theme.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                interactionSource = interaction,
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty()) {
                            Text(text = placeholder, style = MangoType.body, color = theme.textTertiary)
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}

/** Loading placeholder for the search-results grid: shapes mirror [CoverCard]'s cover + title. */
@Composable
private fun CoverGridSkeleton(modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = BROWSE_COVER_WIDTH),
        contentPadding = PaddingValues(0.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = modifier,
        userScrollEnabled = false,
    ) {
        items(10) {
            Column {
                SkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(MangoRadius.row)),
                )
                Spacer(modifier = Modifier.height(6.dp))
                SkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )
            }
        }
    }
}

/** Loading placeholder for discover sections: shapes mirror [BrowseSectionRow]'s title + shelf. */
@Composable
private fun SectionsSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(MangoSpace.md)) {
        repeat(2) {
            Column {
                SkeletonBlock(
                    modifier = Modifier
                        .width(120.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )
                Spacer(modifier = Modifier.height(MangoSpace.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    repeat(5) {
                        SkeletonBlock(
                            modifier = Modifier
                                .width(BROWSE_COVER_WIDTH)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(MangoRadius.row)),
                        )
                    }
                }
            }
        }
    }
}

/** One discover/home section: a title above a horizontal shelf of covers. */
@Composable
private fun BrowseSectionRow(section: HomeSection, onOpenDetails: (MangaEntry) -> Unit) {
    val theme = LocalMangoTheme.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = section.title, style = MangoType.bodyStrong, color = theme.textPrimary)
        Spacer(modifier = Modifier.height(MangoSpace.xs))
        // no item keys: extension data is untrusted and a duplicate mangaId in one
        // response must not crash composition with a duplicate-key exception
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(section.items) { entry ->
                CoverCard(
                    title = entry.title,
                    coverUrl = entry.cover,
                    // MangaEntry carries no chapter/genre metadata to show here or on hover.
                    metaLine = "",
                    unreadCount = null,
                    progress = null,
                    finished = false,
                    onClick = { onOpenDetails(entry) },
                    modifier = Modifier.width(BROWSE_COVER_WIDTH),
                )
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
    val theme = LocalMangoTheme.current

    Surface(modifier = Modifier.fillMaxSize(), color = theme.bg0) {
        ContentColumn(max = MangoSpace.gridMaxWidth) {
            Column(modifier = Modifier.fillMaxSize().padding(MangoSpace.md)) {
                val selectedSource = sources.firstOrNull { it.sourceId == selectedSourceId }
                if (selectedSource != null) {
                    // "updated …" caption from board 07 is skipped: SourceInfo carries no such timestamp.
                    Text(text = selectedSource.name, style = MangoType.title, color = theme.textPrimary)
                    Spacer(modifier = Modifier.height(MangoSpace.sm))
                }
                if (sources.isNotEmpty()) {
                    SegmentedControl(
                        options = sources.map { it.name },
                        selectedIndex = sources.indexOfFirst { it.sourceId == selectedSourceId },
                        onSelect = { index -> onSelectSource(sources[index].sourceId) },
                    )
                    Spacer(modifier = Modifier.height(MangoSpace.sm))
                }
                StyledSearchField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = "Search…",
                    onSearch = onSearch,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(MangoSpace.md))
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (selectedSourceId == null) {
                        // nothing to browse: either the registry read failed (say so honestly) or
                        // there are genuinely no sources — never the sections-mode hint
                        Text(
                            text = sourcesError ?: "No sources installed — add one from the Extensions tab",
                            style = MangoType.body,
                            color = if (sourcesError != null) theme.danger else theme.textSecondary,
                        )
                    } else if (searchActive) {
                        when {
                            isLoading -> CoverGridSkeleton(modifier = Modifier.fillMaxSize())
                            error != null -> ChallengeErrorContent(
                                error = error,
                                challengeUrl = challengeUrl,
                                solving = solving,
                                solveEnabled = solveEnabled,
                                onSolveChallenge = onSolveChallenge,
                            )
                            results.isEmpty() -> EmptyState(
                                title = "No results",
                                guidance = "Try a different search term.",
                            )
                            // no item keys: extension data is untrusted and a duplicate mangaId in one
                            // response must not crash composition with a duplicate-key exception
                            else -> LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = BROWSE_COVER_WIDTH),
                                contentPadding = PaddingValues(0.dp),
                                horizontalArrangement = Arrangement.spacedBy(18.dp),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(results) { entry ->
                                    CoverCard(
                                        title = entry.title,
                                        coverUrl = entry.cover,
                                        metaLine = "",
                                        unreadCount = null,
                                        progress = null,
                                        finished = false,
                                        onClick = { onOpenDetails(entry) },
                                    )
                                }
                            }
                        }
                    } else {
                        when {
                            sectionsLoading -> SectionsSkeleton(modifier = Modifier.fillMaxSize())
                            sectionsError != null -> ChallengeErrorContent(
                                error = sectionsError,
                                challengeUrl = challengeUrl,
                                solving = solving,
                                solveEnabled = solveEnabled,
                                onSolveChallenge = onSolveChallenge,
                            )
                            sections.isNotEmpty() -> LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(MangoSpace.md),
                            ) {
                                // no item keys: extension-provided section ids are untrusted and a
                                // duplicate must not crash composition with a duplicate-key exception
                                items(sections) { section ->
                                    BrowseSectionRow(section = section, onOpenDetails = onOpenDetails)
                                }
                            }
                            else -> EmptyState(
                                title = "No discover sections — search this source instead",
                                guidance = "Try the search field above.",
                            )
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
    // a real gate into the solver would be needed if that assumption ever breaks
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
            // Deliberate way back to sections mode: there is no explicit clear button, so
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
