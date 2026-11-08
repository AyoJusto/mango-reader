package dev.mango.app

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
import androidx.compose.ui.draw.clip
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

private val BROWSE_COVER_WIDTH = 190.dp

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

@Composable
private fun BrowseSectionRow(section: HomeSection, onOpenDetails: (MangaEntry) -> Unit) {
    val theme = LocalMangoTheme.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = section.title, style = MangoType.bodyStrong, color = theme.textPrimary)
        Spacer(modifier = Modifier.height(MangoSpace.xs))
        // No item keys: untrusted duplicate mangaIds must not crash composition.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(section.items) { entry ->
                CoverCard(
                    title = entry.title,
                    coverUrl = entry.cover,
                    sourceId = entry.sourceId,
                    mangaId = entry.mangaId,
                    unreadCount = null,
                    finished = false,
                    onClick = { onOpenDetails(entry) },
                    modifier = Modifier.width(BROWSE_COVER_WIDTH),
                )
            }
        }
    }
}

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
    val solving = solvingSourceId != null && solvingSourceId == selectedSourceId
    val solveEnabled = solvingSourceId == null
    val theme = LocalMangoTheme.current

    Surface(modifier = Modifier.fillMaxSize(), color = theme.bg0) {
        ContentColumn(max = MangoSpace.gridMaxWidth) {
            Column(modifier = Modifier.fillMaxSize().padding(MangoSpace.md)) {
                if (sources.isNotEmpty()) {
                    SegmentedControl(
                        options = sources.map { it.name },
                        selectedIndex = sources.indexOfFirst { it.sourceId == selectedSourceId },
                        onSelect = { index -> onSelectSource(sources[index].sourceId) },
                    )
                    Spacer(modifier = Modifier.height(MangoSpace.sm))
                }
                KitSearchField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = "Search…",
                    onSearch = onSearch,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(MangoSpace.md))
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (selectedSourceId == null) {
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
                            // No item keys: untrusted duplicate mangaIds must not crash composition.
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
                                        sourceId = entry.sourceId,
                                        mangaId = entry.mangaId,
                                        unreadCount = null,
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
                                // No item keys: untrusted duplicate section ids must not crash composition.
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

class BrowseState {
    var sources by mutableStateOf<List<SourceInfo>>(emptyList())
    var selectedSourceId by mutableStateOf<String?>(null)
    var query by mutableStateOf("")

    var sourcesError by mutableStateOf<String?>(null)

    var sectionsBySource by mutableStateOf<Map<String, List<HomeSection>>>(emptyMap())
    var sectionsPending by mutableStateOf<Set<String>>(emptySet())
    var sectionsErrors by mutableStateOf<Map<String, String>>(emptyMap())

    // Split by mode so a stale search challenge never renders Solve on a sections error.
    var sectionsChallengeUrls by mutableStateOf<Map<String, String>>(emptyMap())

    // One solve at a time relies on single-pane navigation; move the gate into the solver if that changes.
    var solvingSourceId by mutableStateOf<String?>(null)

    var results by mutableStateOf<List<MangaEntry>>(emptyList())
    var isLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var searchChallengeUrl by mutableStateOf<String?>(null)
    var searchedSourceId by mutableStateOf<String?>(null)

    // The post-solve rerun uses this, never the live text field.
    var searchedQuery: String = ""

    // Cancelled on a new submit so a slow older search can't overwrite newer results.
    var searchJob: Job? = null
}

@Composable
fun BrowseScreen(
    catalog: CatalogRepository,
    challengeSolver: ChallengeSolver,
    state: BrowseState,
    onOpenDetails: (MangaEntry) -> Unit,
) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // Reload on every entry so newly installed extensions appear without a restart.
        try {
            state.sources = catalog.installedSources()
            state.sourcesError = null
            if (state.sources.none { it.sourceId == state.selectedSourceId }) {
                state.selectedSourceId = state.sources.firstOrNull()?.sourceId
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the previous list; a local DB hiccup must not crash the app out of a LaunchedEffect.
            state.sourcesError = e.message ?: "Failed to load sources"
            Logger.getLogger("BrowseScreen").log(Level.WARNING, "failed to load installed sources", e)
        }
    }

    // Clears its own stale error and challenge url so no old Solve button renders.
    suspend fun fetchSections(sourceId: String) {
        state.sectionsPending += sourceId
        state.sectionsErrors -= sourceId
        state.sectionsChallengeUrls -= sourceId
        try {
            val loaded = catalog.homeSections(sourceId)
            state.sectionsBySource += (sourceId to loaded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChallengeRequiredException) {
            state.sectionsErrors += (sourceId to "Protected by Cloudflare")
            e.url?.let { url -> state.sectionsChallengeUrls += (sourceId to url) }
        } catch (e: Exception) {
            state.sectionsErrors += (sourceId to (e.message ?: "Failed to load"))
        } finally {
            state.sectionsPending -= sourceId
        }
    }

    // Captured at composition and used as the effect key: a live state read would double-fetch.
    val selected = state.selectedSourceId

    LaunchedEffect(selected) {
        // Selected tab only, on demand: never prefetch other sources (polite-scraper rule).
        val sourceId = selected
        if (sourceId != null && sourceId !in state.sectionsBySource && sourceId !in state.sectionsPending) {
            // Launched in the screen scope so a chip flip doesn't cancel an in-flight load.
            scope.launch { fetchSections(sourceId) }
        }
    }

    // Touches only search-mode challenge state, never the sections map.
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
            // Blank submit is the way back to sections mode; there is no clear button.
            state.searchJob?.cancel()
            state.results = emptyList()
            state.error = null
            state.searchedSourceId = null
            return
        }
        val query = state.query
        state.searchedSourceId = sourceId
        state.searchedQuery = query
        state.searchJob?.cancel()
        state.searchJob = scope.launch { searchOnce(sourceId, query) }
    }

    // fromSearchMode is captured at click time so the rerun targets the mode the user saw.
    fun solveChallenge(sourceId: String, fromSearchMode: Boolean) {
        val url = (if (fromSearchMode) state.searchChallengeUrl else state.sectionsChallengeUrls[sourceId])
            ?: return
        scope.launch {
            state.solvingSourceId = sourceId
            try {
                if (challengeSolver.solve(sourceId, url)) {
                    // Polite-scraper rule: rerun only this source's fetch.
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
