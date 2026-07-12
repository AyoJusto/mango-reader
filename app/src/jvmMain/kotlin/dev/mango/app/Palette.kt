package dev.mango.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mango.core.domain.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.logging.Level
import java.util.logging.Logger

/** One candidate result in the palette: what a row shows and what Enter/click does. */
data class PaletteHit(
    val category: String, // display group: "Screens", "Appearance", "Manhwa"
    val title: String,
    val subtitle: String? = null,
    // A right-aligned keycap hint (e.g. a shortcut), shown only when the hit carries one.
    val hint: String? = null,
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
    // Providers ignore the query, hence query("").
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

/** Category-to-glyph map for [PaletteResultTile]; a category with no entry falls back to the hit title's initial letter. */
private val PALETTE_CATEGORY_GLYPHS = mapOf(
    "Screens" to "▤",
    "Appearance" to "◐",
    "Settings" to "⚙",
    "Actions" to "⚡",
)

private val PALETTE_PANEL_WIDTH = 660.dp
private val PALETTE_PANEL_TOP_OFFSET = 120.dp
private val PALETTE_RESULTS_MAX_HEIGHT = 600.dp

/** The input row's glyph, query text, and placeholder all share this size — the palette's largest text. */
private val PALETTE_INPUT_TYPE = TextStyle(fontSize = 19.sp)

/**
 * Pure, data-driven content — the screenshot harness renders this directly. Full-screen scrim
 * (click closes) with a centered, Spotlight-styled panel: an autofocused search field, filter
 * tab pills, and a text-only [LazyColumn] of hits (no cover images anywhere — an IntelliJ-style
 * list is meant to be scanned fast, and extension covers are untrusted network fetches this
 * palette has no business making — every category, including manhwa, renders a glyph tile
 * instead). No item keys: hit titles come from extension-provided library data, and a duplicate
 * title must not crash composition with a duplicate-key exception (same rationale as
 * Browse/Search's result grids).
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
    val theme = LocalMangoTheme.current
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
            // scrim role, not a Color literal: Theme.kt owns every color decision
            .background(theme.bg0.copy(alpha = 0.55f))
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
        val panelVisible = remember { MutableTransitionState(false) }.apply { targetState = true }
        AnimatedVisibility(
            visibleState = panelVisible,
            modifier = Modifier.padding(top = PALETTE_PANEL_TOP_OFFSET),
            enter = scaleIn(
                initialScale = 0.98f,
                animationSpec = tween(MangoMotion.PALETTE_OPEN_MS, easing = MangoMotion.decel),
            ) + fadeIn(
                animationSpec = tween(MangoMotion.PALETTE_OPEN_MS, easing = MangoMotion.decel),
            ),
        ) {
            Box(
                modifier = Modifier
                    .width(PALETTE_PANEL_WIDTH)
                    .shadow(elevation = 32.dp, shape = RoundedCornerShape(MangoRadius.large))
                    .clip(RoundedCornerShape(MangoRadius.large))
                    .background(theme.overlay)
                    .border(1.dp, theme.divider, RoundedCornerShape(MangoRadius.large))
                    // consumes the click so it doesn't fall through to the scrim's onDismiss above
                    .clickable(onClick = {}),
            ) {
                Column(modifier = Modifier.padding(MangoSpace.md)) {
                    PaletteInputRow(query = query, onQueryChange = onQueryChange, focusRequester = focusRequester)
                    Spacer(modifier = Modifier.height(MangoSpace.xs))
                    PaletteFilterTabs(tabNames = tabNames, activeTabIndex = activeTabIndex, onTabIndexChange = onTabIndexChange)
                    Spacer(modifier = Modifier.height(MangoSpace.xs))
                    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = PALETTE_RESULTS_MAX_HEIGHT)) {
                        itemsIndexed(hits) { index, hit ->
                            PaletteResultRow(hit = hit, selected = index == selectedIndex, onClick = { onRunHit(hit) })
                        }
                    }
                    PaletteFooter(resultCount = hits.size)
                }
            }
        }
    }
}

/**
 * The palette's input row: magnifier glyph, the query field, and a trailing esc keycap. A bare
 * [BasicTextField] — the spec has no border/fill chrome around it, just glyph + text + keycap —
 * with the placeholder composed through [BasicTextField]'s own decorationBox so it merges into
 * the field's semantics node the same way Material's TextField does (preserving every existing
 * `onNodeWithText("Search everywhere…").performTextInput(...)` call across the flow tests).
 */
@Composable
private fun PaletteInputRow(query: String, onQueryChange: (String) -> Unit, focusRequester: FocusRequester) {
    val theme = LocalMangoTheme.current
    // The 19sp input size is palette-specific, but the face must follow the app font choice.
    val inputType = PALETTE_INPUT_TYPE.copy(fontFamily = MangoType.body.fontFamily)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm)) {
        Text(text = "⌕", style = inputType, color = theme.textTertiary)
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            singleLine = true,
            textStyle = inputType.copy(color = theme.textPrimary),
            cursorBrush = SolidColor(theme.accent),
            decorationBox = { innerTextField ->
                if (query.isEmpty()) {
                    Text(text = "Search everywhere…", style = inputType, color = theme.textTertiary)
                }
                innerTextField()
            },
        )
        Keycap("esc")
    }
}

/** Filter tabs as accent-fill/surface-fill pills; Tab cycling is handled by the caller, this is click-only. */
@Composable
private fun PaletteFilterTabs(tabNames: List<String>, activeTabIndex: Int, onTabIndexChange: (Int) -> Unit) {
    val theme = LocalMangoTheme.current
    Row(horizontalArrangement = Arrangement.spacedBy(MangoSpace.xs)) {
        tabNames.forEachIndexed { index, name ->
            val active = index == activeTabIndex
            Pill(
                text = name,
                container = if (active) theme.accent else theme.surface,
                content = if (active) theme.accentOn else theme.textSecondary,
                modifier = Modifier.clickable { onTabIndexChange(index) },
            )
        }
    }
}

/** One result row: a glyph tile, primary/secondary text, and an optional right-aligned keycap hint. */
@Composable
private fun PaletteResultRow(hit: PaletteHit, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalMangoTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MangoRadius.control))
            .background(if (selected) theme.accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = MangoSpace.sm, vertical = MangoSpace.base),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm),
    ) {
        PaletteResultTile(category = hit.category, title = hit.title)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = hit.title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = theme.textPrimary)
            Text(text = hit.subtitle ?: hit.category, style = MangoType.meta, color = theme.textTertiary)
        }
        hit.hint?.let { hint -> Keycap(hint) }
    }
}

/** A 26 dp glyph tile: a category glyph where one is defined, otherwise the hit title's initial letter. */
@Composable
private fun PaletteResultTile(category: String, title: String) {
    val theme = LocalMangoTheme.current
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(MangoRadius.keycap))
            .background(theme.textPrimary.copy(alpha = 0.07f)),
        contentAlignment = Alignment.Center,
    ) {
        val glyph = PALETTE_CATEGORY_GLYPHS[category] ?: title.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        Text(text = glyph, style = MangoType.label, color = theme.textSecondary)
    }
}

/** Hairline divider, keyboard hints, and the result count. */
@Composable
private fun PaletteFooter(resultCount: Int) {
    val theme = LocalMangoTheme.current
    Column(modifier = Modifier.padding(top = MangoSpace.sm)) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(theme.divider))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = MangoSpace.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "↑↓ navigate · ↵ open · tab next filter", style = MangoType.hint, color = theme.textTertiary)
            Text(text = resultCount.toString(), style = MangoType.hint, color = theme.textTertiary)
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

/** Every built-in accent preset as a palette hit; run applies it to the current theme immediately. */
private fun accentProvider(theme: MangoTheme, onThemeChange: (MangoTheme) -> Unit): PaletteProvider =
    PaletteProvider { _ ->
        ACCENT_PRESETS.map { (label, color) ->
            PaletteHit(
                category = "Appearance",
                title = "Accent: $label",
                run = { onThemeChange(theme.copy(accent = color)) },
            )
        }
    }

/** One-off app actions as palette hits; run invokes the callback threaded from the shell. */
private fun actionsProvider(onToggleSidebar: () -> Unit, onToggleLibraryView: () -> Unit): PaletteProvider =
    PaletteProvider { _ ->
        listOf(
            PaletteHit(category = "Actions", title = "Toggle sidebar", run = onToggleSidebar),
            PaletteHit(category = "Actions", title = "Toggle library view", run = onToggleLibraryView),
        )
    }

/** Every registered settings entry as a palette hit; run opens the Settings screen. */
private fun settingsProvider(navigate: (Screen) -> Unit): PaletteProvider = PaletteProvider { _ ->
    SETTINGS_ENTRIES.map { title ->
        PaletteHit(category = "Settings", title = "Setting: $title", run = { navigate(Screen.Settings) })
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
 * The tab set: "All" fans out to every provider, "Manhwa" is just the library provider,
 * "Actions" is screens + accents + settings + one-off actions. A tab-bar from day one so a
 * future online-search tab slots in later without rework.
 */
fun paletteTabs(
    library: LibraryRepository,
    navigate: (Screen) -> Unit,
    theme: MangoTheme,
    onThemeChange: (MangoTheme) -> Unit,
    onToggleSidebar: () -> Unit = {},
    onToggleLibraryView: () -> Unit = {},
): List<PaletteTab> {
    val screens = screenProvider(navigate)
    val accents = accentProvider(theme, onThemeChange)
    val manhwa = libraryProvider(library, navigate)
    val settings = settingsProvider(navigate)
    val actions = actionsProvider(onToggleSidebar, onToggleLibraryView)
    return listOf(
        PaletteTab("All", listOf(screens, accents, manhwa, settings, actions)),
        PaletteTab("Manhwa", listOf(manhwa)),
        PaletteTab("Actions", listOf(screens, accents, settings, actions)),
    )
}
