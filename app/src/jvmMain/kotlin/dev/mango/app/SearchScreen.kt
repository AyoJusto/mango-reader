package dev.mango.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.ChallengeRequiredException
import dev.mango.core.domain.ChallengeSolver
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.SourceInfo
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.logging.Level
import java.util.logging.Logger

private const val SEARCH_HISTORY_MAX = 10

/** Newest first, deduped by exact query text, capped at 10; re-running a query moves it to the top. */
internal fun recordSearch(history: List<SearchHistoryEntry>, query: String, at: Long): List<SearchHistoryEntry> {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return history
    val withoutDupe = history.filterNot { it.query == trimmed }
    return (listOf(SearchHistoryEntry(trimmed, at)) + withoutDupe).take(SEARCH_HISTORY_MAX)
}

/** Removes the entry matching [query] exactly, if present. */
internal fun removeSearch(history: List<SearchHistoryEntry>, query: String): List<SearchHistoryEntry> =
    history.filterNot { it.query == query }

/** One recent-search row: clock glyph, query, relative time, and a remove ✕ that only shows on row hover. */
@Composable
private fun SearchHistoryRow(entry: SearchHistoryEntry, now: Instant, onReplay: () -> Unit, onRemove: () -> Unit) {
    val theme = LocalMangoTheme.current
    val hover = rememberHoverFill(rest = theme.bg1.copy(alpha = 0f), hover = theme.bg1)
    val rowHovered by hover.interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MangoRadius.control))
            .background(hover.fill)
            .hoverable(hover.interaction)
            .clickable(interactionSource = hover.interaction, indication = null, onClick = onReplay)
            .padding(horizontal = MangoSpace.sm, vertical = 9.dp)
            .testTag("search-history-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm),
    ) {
        Text(text = "◷", fontSize = 13.sp, color = theme.textTertiary)
        Text(
            text = entry.query,
            fontSize = 13.5.sp,
            color = theme.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatRelativeTime(Instant.fromEpochMilliseconds(entry.at), now),
            fontSize = 11.5.sp,
            color = theme.textTertiary,
        )
        val removeHover = rememberHoverFill(rest = theme.bg2.copy(alpha = 0f), hover = theme.bg2)
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(MangoRadius.keycap))
                .background(removeHover.fill)
                .hoverable(removeHover.interaction)
                // A separate clickable from the row's above: pointer input on this inner Box
                // consumes the click before it reaches the row's clickable, so removing an
                // entry never also replays it. Enabled only while the row is hovered — an
                // invisible ✕ must not swallow a replay click on the row's right edge.
                .clickable(
                    interactionSource = removeHover.interaction,
                    indication = null,
                    enabled = rowHovered,
                    onClick = onRemove,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "✕", fontSize = 12.sp, color = theme.textTertiary, modifier = Modifier.alpha(if (rowHovered) 1f else 0f))
        }
    }
}

/** The empty-query idle state's Recent-searches list: header ("Recent" / "Clear all") plus up to 10 rows. */
@Composable
private fun SearchHistorySection(
    history: List<SearchHistoryEntry>,
    onReplaySearch: (String) -> Unit,
    onRemoveSearch: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    val theme = LocalMangoTheme.current
    val now = remember { Clock.System.now() }
    Column(modifier = Modifier.fillMaxWidth().testTag("search-history")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MangoSpace.sm).padding(bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Recent", style = MangoType.microLabel, color = theme.textTertiary, modifier = Modifier.weight(1f))
            val clearInteraction = remember { MutableInteractionSource() }
            val clearHovered by clearInteraction.collectIsHoveredAsState()
            Text(
                text = "Clear all",
                fontSize = 11.5.sp,
                color = if (clearHovered) theme.textPrimary else theme.textTertiary,
                modifier = Modifier
                    .hoverable(clearInteraction)
                    .clickable(interactionSource = clearInteraction, indication = null, onClick = onClearHistory),
            )
        }
        history.forEach { entry ->
            SearchHistoryRow(
                entry = entry,
                now = now,
                onReplay = { onReplaySearch(entry.query) },
                onRemove = { onRemoveSearch(entry.query) },
            )
        }
    }
}

/** A result row: 30x42 thumb, title, alpha-only bg1 hover — no ripple, the fill fade is the indication. */
@Composable
private fun SearchResultRow(entry: MangaEntry, onClick: () -> Unit) {
    val theme = LocalMangoTheme.current
    val hover = rememberHoverFill(rest = theme.bg1.copy(alpha = 0f), hover = theme.bg1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MangoRadius.row))
            .background(hover.fill)
            .hoverable(hover.interaction)
            .clickable(interactionSource = hover.interaction, indication = null, onClick = onClick)
            .padding(horizontal = MangoSpace.sm, vertical = MangoSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm),
    ) {
        Box(
            modifier = Modifier
                .size(width = 30.dp, height = 42.dp)
                .clip(RoundedCornerShape(MangoRadius.keycap))
                .background(theme.bg2),
        ) {
            val cover = entry.cover
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = entry.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // MangaEntry carries no chapter-count/genre metadata for the board 07 subtitle line.
        Text(
            text = entry.title,
            style = MangoType.bodyStrong,
            color = theme.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Loading placeholder for a source's result rows: shapes mirror [SearchResultRow]'s thumb + title. */
@Composable
private fun SearchResultsSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(MangoSpace.xs)) {
        repeat(3) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm),
                modifier = Modifier.padding(horizontal = MangoSpace.sm, vertical = MangoSpace.xs),
            ) {
                SkeletonBlock(
                    modifier = Modifier
                        .size(width = 30.dp, height = 42.dp)
                        .clip(RoundedCornerShape(MangoRadius.keycap)),
                )
                SkeletonBlock(
                    modifier = Modifier
                        .width(140.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )
            }
        }
    }
}

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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MangoSpace.xs)) {
            Text(text = source.name, style = MangoType.microLabel, color = theme.textTertiary)
            if (pending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = theme.accent,
                    strokeWidth = 2.dp,
                    trackColor = Color.Transparent,
                )
            } else if (error == null) {
                Text(
                    text = "${results.size} result${if (results.size == 1) "" else "s"}",
                    style = MangoType.caption,
                    color = theme.textSecondary,
                )
            }
        }
        Spacer(modifier = Modifier.height(MangoSpace.xs))
        when {
            pending -> SearchResultsSkeleton()
            // the user did nothing wrong here, so a Cloudflare challenge reads as warning, never
            // danger — see ChallengeErrorContent; any other failure stays plain danger text below
            error != null && challengeUrl != null -> ChallengeErrorContent(
                error = error,
                challengeUrl = challengeUrl,
                solving = solving,
                solveEnabled = solveEnabled,
                onSolveChallenge = onSolveChallenge,
            )
            error != null -> Text(text = error, style = MangoType.body, color = theme.danger)
            results.isEmpty() -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(title = "No results", guidance = "Try a different search term.")
            }
            // no item keys: extension data is untrusted and a duplicate mangaId in one
            // response must not crash composition with a duplicate-key exception
            else -> Column {
                results.forEach { entry ->
                    SearchResultRow(entry = entry, onClick = { onOpenDetails(entry) })
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
    history: List<SearchHistoryEntry> = emptyList(),
    onReplaySearch: (String) -> Unit = {},
    onRemoveSearch: (String) -> Unit = {},
    onClearHistory: () -> Unit = {},
) {
    val theme = LocalMangoTheme.current
    Surface(modifier = Modifier.fillMaxSize(), color = theme.bg0) {
        ContentColumn(max = MangoSpace.gridMaxWidth) {
            Column(modifier = Modifier.fillMaxSize().padding(MangoSpace.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KitSearchField(
                        value = query,
                        onValueChange = onQueryChange,
                        placeholder = "Search all sources…",
                        onSearch = onSearch,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(MangoSpace.sm))
                    Text(
                        text = "${sources.size} source${if (sources.size == 1) "" else "s"}",
                        style = MangoType.caption,
                        color = theme.textTertiary,
                    )
                }
                Spacer(modifier = Modifier.height(MangoSpace.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(MangoSpace.xs)) {
                    sources.forEach { source ->
                        val selected = source.sourceId in enabledSourceIds
                        Pill(
                            text = source.name,
                            container = if (selected) theme.accent.copy(alpha = 0.14f) else theme.surface,
                            content = if (selected) theme.accent else theme.textSecondary,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onToggleSource(source.sourceId) },
                            ),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(MangoSpace.md))
                // no per-source data and nothing in flight means no search has run yet: show an
                // idle hint instead of a "No results" section per source. There is deliberately
                // no whole-screen spinner: each source's section appears the moment that source
                // answers, so one slow extension never blocks the others' results.
                val hasSearched =
                    resultsBySource.isNotEmpty() || errorsBySource.isNotEmpty() || pendingSourceIds.isNotEmpty()
                if (query.isEmpty() && history.isNotEmpty()) {
                    SearchHistorySection(
                        history = history,
                        onReplaySearch = onReplaySearch,
                        onRemoveSearch = onRemoveSearch,
                        onClearHistory = onClearHistory,
                    )
                } else if (!hasSearched) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (sources.isNotEmpty() && enabledSourceIds.isEmpty()) {
                                "No sources selected"
                            } else {
                                "Search across all installed sources"
                            },
                            style = MangoType.body,
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
                    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(MangoSpace.lg)) {
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
    searchHistory: List<SearchHistoryEntry> = emptyList(),
    onSearchHistoryChange: (List<SearchHistoryEntry>) -> Unit = {},
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
            state.results += (sourceId to found)
            state.errors -= sourceId
            state.challengeUrls -= sourceId
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChallengeRequiredException) {
            state.errors += (sourceId to "Protected by Cloudflare")
            e.url?.let { url -> state.challengeUrls += (sourceId to url) }
        } catch (e: Exception) {
            state.errors += (sourceId to (e.message ?: "Search failed"))
        } finally {
            state.pendingSourceIds -= sourceId
        }
    }

    fun search() {
        val enabled = state.enabledSourceIds
        val query = state.query
        // nothing enabled: the idle hint already reads "No sources selected"
        if (enabled.isEmpty()) return
        if (query.isNotBlank()) {
            onSearchHistoryChange(recordSearch(searchHistory, query, Clock.System.now().toEpochMilliseconds()))
        }
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
        history = searchHistory,
        onReplaySearch = { query -> state.query = query; search() },
        onRemoveSearch = { query -> onSearchHistoryChange(removeSearch(searchHistory, query)) },
        onClearHistory = { onSearchHistoryChange(emptyList()) },
    )
}
