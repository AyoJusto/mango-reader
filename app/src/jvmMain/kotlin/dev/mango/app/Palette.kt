package dev.mango.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.mango.core.domain.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.logging.Level
import java.util.logging.Logger

/** One candidate result in the palette: what a row shows and what Enter/click does. */
data class PaletteHit(
    val category: String, // display group: "Screens", "Themes", "Manhwa"
    val title: String,
    val subtitle: String? = null,
    val run: () -> Unit,
)

/**
 * A source of palette candidates. Providers are dumb on purpose: candidates are fetched once
 * per palette open (and per tab switch), then filtered and ranked centrally by [fuzzyScore] on
 * every keystroke — providers are never re-queried while the user types, which keeps every
 * provider a one-liner instead of reimplementing fuzzy matching (or fetch throttling) per
 * feature. To wire up a new searchable feature, implement this and add one line to
 * [paletteTabs]; to add a new mode (its own tab in the palette), add a new [PaletteTab]
 * instead. A future tab whose providers need the live query (e.g. online search) will add a
 * per-tab fetch policy then — not before.
 */
fun interface PaletteProvider {
    /** Return candidate hits for [query]; the palette fuzzy-filters and ranks them centrally. */
    suspend fun query(query: String): List<PaletteHit>
}

/** One tab in the palette: a name shown in the tab row and the providers it fans out to. */
data class PaletteTab(val name: String, val providers: List<PaletteProvider>)

private const val WORD_START_BONUS = 5
private const val PREFIX_BONUS = 20
private val WORD_BOUNDARY_CHARS = charArrayOf(' ', '-', '_', ':')

/**
 * Null unless every character of [query] appears in [text] in order (case-insensitive
 * subsequence match) — that null is the palette's whole filter. When it does match, higher is
 * better: consecutive runs of matched characters and a match landing right after a word
 * boundary (space/-/_/:) add bonus points, and [text] starting with [query] outright adds a
 * flat bonus. The greedy alignment is retried once per occurrence of the FIRST query character
 * in [text] and the best score wins (so "lev" anchors at "Leveling", not the stray 'l' in
 * "Television"); later characters are still matched greedily — a deliberate ceiling, full DP
 * alignment only if ranking complaints appear. A blank query matches everything at score 0.
 */
fun fuzzyScore(query: String, text: String): Int? {
    if (query.isBlank()) return 0
    val q = query.lowercase()
    val t = text.lowercase()

    var best = -1
    var anchor = t.indexOf(q[0])
    while (anchor != -1) {
        val score = greedyScoreFrom(q, t, anchor)
        if (score != null && score > best) best = score
        anchor = t.indexOf(q[0], anchor + 1)
    }
    if (best < 0) return null
    return if (t.startsWith(q)) best + PREFIX_BONUS else best
}

/** One greedy left-to-right alignment of [q] into [t] with the first char pinned at [anchor]. */
private fun greedyScoreFrom(q: String, t: String, anchor: Int): Int? {
    var score = 0
    var searchFrom = anchor
    var runLength = 0
    for (ch in q) {
        val idx = t.indexOf(ch, searchFrom)
        if (idx == -1) return null
        runLength = if (idx == searchFrom) runLength + 1 else 1
        score += runLength
        if (idx == 0 || t[idx - 1] in WORD_BOUNDARY_CHARS) score += WORD_START_BONUS
        searchFrom = idx + 1
    }
    return score
}

/** Test hook: scopes [PaletteFlowTest]'s node lookups to just the palette overlay's subtree. */
internal const val PALETTE_TEST_TAG = "mango-palette"

private const val PALETTE_MAX_HITS = 50

/** Score, filter, and order [candidates] for [query]: score desc, then category, then title; cap 50. */
internal fun rankHits(candidates: List<PaletteHit>, query: String): List<PaletteHit> =
    candidates
        .mapNotNull { hit -> fuzzyScore(query, hit.title)?.let { score -> score to hit } }
        .sortedWith(
            compareByDescending<Pair<Int, PaletteHit>> { it.first }
                .thenBy { it.second.category }
                .thenBy { it.second.title },
        )
        .map { it.second }
        .take(PALETTE_MAX_HITS)

/**
 * Palette's screen state, hoisted so [AppShell] can [remember] it once (see [BrowseState] for
 * the same idiom) and so Main.kt's double-shift detector can flip [visible] directly.
 */
class PaletteState {
    var visible by mutableStateOf(false)
    var query by mutableStateOf("")
    var activeTabIndex by mutableStateOf(0)

    // raw provider output for the active tab, fetched once per open/tab-switch; the rendered
    // list is derived from this synchronously per keystroke (see PaletteOverlay's rankHits)
    var candidates by mutableStateOf<List<PaletteHit>>(emptyList())

    var selectedIndex by mutableStateOf(0)
}

/**
 * Stateful orchestration: fetches the active tab's candidates once per open/tab-switch (never
 * per keystroke), ranks them synchronously as the user types, and renders [PaletteContent].
 * A provider that throws contributes nothing (logged) — see the per-provider try/catch below —
 * so one broken provider never blanks the rest.
 */
@Composable
fun PaletteOverlay(state: PaletteState, tabs: List<PaletteTab>) {
    if (!state.visible) return

    // Opening always starts from a clean slate — a previous session's query/tab/selection and
    // stale candidates (still rendered and Enter-runnable otherwise) must never leak into the
    // next open. Plain remember{}: the early-return above removes this subtree from
    // composition while closed, so the initializer re-runs on every open; a key would be dead
    // weight (visible is always true here).
    remember {
        state.query = ""
        state.activeTabIndex = 0
        state.selectedIndex = 0
        state.candidates = emptyList()
    }

    // Candidates are fetched once per tab — and once per open, since this overlay only
    // composes while visible, so re-entering composition restarts the effect. The effect
    // restart IS the cancellation of an in-flight fetch: no manual Job, no scope.launch.
    // v1 providers ignore the query, hence query("").
    LaunchedEffect(state.activeTabIndex) {
        val providers = tabs.getOrNull(state.activeTabIndex)?.providers.orEmpty()
        val collected = mutableListOf<PaletteHit>()
        for (provider in providers) {
            try {
                collected += provider.query("")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.getLogger("Palette").log(Level.WARNING, "palette provider failed", e)
            }
        }
        state.candidates = collected
    }

    // Ranking is synchronous per keystroke: a few hundred local candidates filter in
    // microseconds, and providers are never touched while typing.
    val hits = remember(state.candidates, state.query) { rankHits(state.candidates, state.query) }

    PaletteContent(
        tabNames = tabs.map { it.name },
        activeTabIndex = state.activeTabIndex,
        onTabIndexChange = { state.activeTabIndex = it; state.selectedIndex = 0 },
        query = state.query,
        onQueryChange = { state.query = it; state.selectedIndex = 0 },
        hits = hits,
        selectedIndex = state.selectedIndex,
        onSelectedIndexChange = { state.selectedIndex = it },
        onRunHit = { hit -> hit.run(); state.visible = false },
        onDismiss = { state.visible = false },
    )
}

/**
 * Pure, data-driven content — the screenshot harness renders this directly. Full-screen scrim
 * (click closes) with a centered panel: a tab row of [FilterChip]s, an autofocused search
 * field, and a text-only [LazyColumn] of hits (no cover images anywhere — an IntelliJ-style
 * list is meant to be scanned fast, and extension covers are untrusted network fetches this
 * palette has no business making). No item keys: hit titles come from extension-provided
 * library data, and a duplicate title must not crash composition with a duplicate-key exception
 * (same rationale as Browse/Search's result grids).
 */
@Composable
fun PaletteContent(
    tabNames: List<String>,
    activeTabIndex: Int,
    onTabIndexChange: (Int) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    hits: List<PaletteHit>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    onRunHit: (PaletteHit) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val listState = rememberLazyListState()

    // Keep the keyboard selection visible, IntelliJ-style: scroll the minimal amount, so
    // moving down pins the selection to the bottom edge instead of jumping it to the top.
    LaunchedEffect(selectedIndex, hits) {
        if (hits.isEmpty()) return@LaunchedEffect
        val layout = listState.layoutInfo
        val visible = layout.visibleItemsInfo
        if (visible.isEmpty()) return@LaunchedEffect
        val info = visible.firstOrNull { it.index == selectedIndex }
        val fullyVisible = info != null &&
            info.offset >= layout.viewportStartOffset &&
            info.offset + info.size <= layout.viewportEndOffset
        if (!fullyVisible) {
            val target = selectedIndex.coerceIn(0, hits.size - 1)
            if (target <= visible.first().index) {
                listState.scrollToItem(target)
            } else {
                listState.scrollToItem((target - (visible.size - 1)).coerceAtLeast(0))
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(PALETTE_TEST_TAG)
            // scrim role, not a Color literal: Theme.kt owns every color decision (F11)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss)
            // On the scrim — an ancestor of everything in the panel — not on the text field:
            // ancestor preview sees keys no matter which descendant (chip, row, field) holds
            // focus, so the palette's keyboard survives focus wandering off the field.
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (keyEvent.key) {
                    Key.DirectionDown -> {
                        if (hits.isNotEmpty()) {
                            onSelectedIndexChange((selectedIndex + 1).coerceAtMost(hits.size - 1))
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        if (hits.isNotEmpty()) {
                            onSelectedIndexChange((selectedIndex - 1).coerceAtLeast(0))
                        }
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        hits.getOrNull(selectedIndex)?.let(onRunHit)
                        true
                    }
                    Key.Escape -> {
                        onDismiss()
                        true
                    }
                    Key.Tab -> {
                        if (tabNames.isNotEmpty()) {
                            onTabIndexChange((activeTabIndex + 1) % tabNames.size)
                        }
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            modifier = Modifier
                .padding(top = 96.dp)
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                // consumes the click so it doesn't fall through to the scrim's onDismiss above
                .clickable(onClick = {}),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    placeholder = { Text("Search everywhere…") },
                    leadingIcon = { Text("⌕") },
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tabNames.forEachIndexed { index, name ->
                        FilterChip(
                            selected = index == activeTabIndex,
                            onClick = { onTabIndexChange(index) },
                            label = { Text(name) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(hits) { index, hit ->
                        val selected = index == selectedIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .clickable { onRunHit(hit) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = hit.category,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.widthIn(min = 72.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = hit.title, style = MaterialTheme.typography.bodyMedium)
                                hit.subtitle?.let { subtitle ->
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The static screen list (in rail order) as palette hits; run navigates the shell to it. */
private fun screenProvider(navigate: (Screen) -> Unit): PaletteProvider {
    val entries = listOf(
        "Library" to Screen.Library,
        "Search" to Screen.Search,
        "Browse" to Screen.Browse,
        "Downloads" to Screen.Downloads,
        "Extensions" to Screen.Extensions,
        "Settings" to Screen.Settings,
    )
    return PaletteProvider { _ ->
        entries.map { (name, screen) -> PaletteHit(category = "Screens", title = name, run = { navigate(screen) }) }
    }
}

/** Every registered color scheme as a palette hit; run applies it immediately (M4.4a). */
private fun themeProvider(onThemeChange: (String) -> Unit): PaletteProvider = PaletteProvider { _ ->
    Themes.schemes.keys.map { name ->
        PaletteHit(category = "Themes", title = "Theme: $name", run = { onThemeChange(name) })
    }
}

/** Every library entry as a palette hit; run opens its Details screen. */
private fun libraryProvider(library: LibraryRepository, navigate: (Screen) -> Unit): PaletteProvider =
    PaletteProvider { _ ->
        library.observeLibrary().first().map { item ->
            PaletteHit(
                category = "Manhwa",
                title = item.entry.title,
                subtitle = item.entry.sourceId,
                run = { navigate(Screen.Details(item.entry.sourceId, item.entry.mangaId, fromBrowse = false)) },
            )
        }
    }

/**
 * The v1 tab set (M6a): "All" fans out to every provider, "Manhwa" is just the library
 * provider, "Actions" is screens + themes. A tab-bar from day one so the online-search tab
 * (PLANNING §12 backlog) slots in later without rework.
 */
fun paletteTabs(
    library: LibraryRepository,
    navigate: (Screen) -> Unit,
    onThemeChange: (String) -> Unit,
): List<PaletteTab> {
    val screens = screenProvider(navigate)
    val themes = themeProvider(onThemeChange)
    val manhwa = libraryProvider(library, navigate)
    return listOf(
        PaletteTab("All", listOf(screens, themes, manhwa)),
        PaletteTab("Manhwa", listOf(manhwa)),
        PaletteTab("Actions", listOf(screens, themes)),
    )
}
