package dev.mango.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
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
import dev.mango.core.domain.CollectionInfo
import dev.mango.core.domain.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.logging.Level
import java.util.logging.Logger

data class PaletteHit(
    val category: String,
    val title: String,
    val subtitle: String? = null,
    val hint: String? = null,
    val run: () -> Unit,
)

/** Providers ignore the query: candidates are fetched once per open/tab, then ranked centrally. */
fun interface PaletteProvider {
    suspend fun query(query: String): List<PaletteHit>
}

data class PaletteTab(val name: String, val providers: List<PaletteProvider>)

private const val WORD_START_BONUS = 5
private const val PREFIX_BONUS = 20
private val WORD_BOUNDARY_CHARS = charArrayOf(' ', '-', '_', ':')

/** Null unless [query] is a case-insensitive subsequence of [text]; greedy alignment, full DP if ranking suffers. */
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

internal const val PALETTE_TEST_TAG = "mango-palette"

private const val PALETTE_MAX_HITS = 50

/** Score desc, then category, then title; capped at 50. */
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

class PaletteState {
    var visible by mutableStateOf(false)
    var query by mutableStateOf("")
    var activeTabIndex by mutableStateOf(0)

    var candidates by mutableStateOf<List<PaletteHit>>(emptyList())

    var selectedIndex by mutableStateOf(0)
}

@Composable
fun PaletteOverlay(state: PaletteState, tabs: List<PaletteTab>) {
    if (!state.visible) return

    // Plain remember: the early return drops this subtree while closed, so it re-inits on every open.
    remember {
        state.query = ""
        state.activeTabIndex = 0
        state.selectedIndex = 0
        state.candidates = emptyList()
    }

    // Effect restart cancels any in-flight fetch; providers ignore the query.
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

private val PALETTE_CATEGORY_GLYPHS = mapOf(
    "Screens" to "▤",
    "Appearance" to "◐",
    "Settings" to "⚙",
    "Actions" to "⚡",
)

private val PALETTE_PANEL_WIDTH = 660.dp
private val PALETTE_PANEL_TOP_OFFSET = 120.dp
private val PALETTE_RESULTS_MAX_HEIGHT = 600.dp

private val PALETTE_INPUT_TYPE = TextStyle(fontSize = 19.sp)

/** Text-only list with no item keys: duplicate extension-supplied titles must not crash composition. */
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
    val motion = LocalMangoMotion.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val listState = rememberLazyListState()

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
            .background(theme.bg0.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss)
            // On the scrim so key preview sees keys wherever focus wanders.
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
            enter = (if (motion.slides) {
                scaleIn(
                    initialScale = 0.98f,
                    animationSpec = tween(motion.paletteOpenMs, easing = motion.decel),
                )
            } else {
                EnterTransition.None
            }) + fadeIn(
                animationSpec = tween(motion.paletteOpenMs, easing = motion.decel),
            ),
        ) {
            Box(
                modifier = Modifier
                    .width(PALETTE_PANEL_WIDTH)
                    .shadow(elevation = 32.dp, shape = RoundedCornerShape(MangoRadius.large))
                    .clip(RoundedCornerShape(MangoRadius.large))
                    .background(theme.overlay)
                    .border(1.dp, theme.divider, RoundedCornerShape(MangoRadius.large))
                    // Consumes the click so it doesn't reach the scrim's onDismiss.
                    .clickable(onClick = {}),
            ) {
                Column(modifier = Modifier.padding(MangoSpace.md)) {
                    PaletteInputRow(query = query, onQueryChange = onQueryChange, focusRequester = focusRequester)
                    Spacer(modifier = Modifier.height(MangoSpace.xs))
                    PaletteFilterTabs(
                        tabNames = tabNames,
                        activeTabIndex = activeTabIndex,
                        onTabIndexChange = onTabIndexChange
                    )
                    Spacer(modifier = Modifier.height(MangoSpace.xs))
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().heightIn(max = PALETTE_RESULTS_MAX_HEIGHT)
                    ) {
                        itemsIndexed(hits) { index, hit ->
                            PaletteResultRow(hit = hit, selected = index == selectedIndex, onClick = { onRunHit(hit) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteInputRow(query: String, onQueryChange: (String) -> Unit, focusRequester: FocusRequester) {
    val theme = LocalMangoTheme.current
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
                modifier = Modifier.keyboardFocusRing(RoundedCornerShape(MangoRadius.pill)).clickable { onTabIndexChange(index) },
            )
        }
    }
}

@Composable
private fun PaletteResultRow(hit: PaletteHit, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalMangoTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .keyboardFocusRing(RoundedCornerShape(MangoRadius.control))
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
            hit.subtitle?.let { Text(text = it, style = MangoType.meta, color = theme.textTertiary) }
        }
        hit.hint?.let { hint -> Keycap(hint) }
    }
}

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

private fun themeProvider(themes: List<MangoTheme>, onThemeChange: (MangoTheme) -> Unit): PaletteProvider =
    PaletteProvider { _ ->
        themes.map { entry ->
            PaletteHit(
                category = "Appearance",
                title = "Theme: ${entry.name}",
                run = { onThemeChange(entry) },
            )
        }
    }

private fun actionsProvider(
    onToggleSidebar: () -> Unit,
    onToggleLibraryView: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onClearSearchHistory: () -> Unit,
    onManageCollections: () -> Unit,
): PaletteProvider =
    PaletteProvider { _ ->
        listOf(
            PaletteHit(category = "Actions", title = "Toggle sidebar", run = onToggleSidebar),
            PaletteHit(category = "Actions", title = "Toggle library view", run = onToggleLibraryView),
            PaletteHit(category = "Actions", title = "Check for updates", run = onCheckForUpdates),
            PaletteHit(category = "Actions", title = "Clear search history", run = onClearSearchHistory),
            PaletteHit(category = "Actions", title = "Manage collections…", run = onManageCollections),
        )
    }

private fun collectionsProvider(
    collections: List<CollectionInfo>,
    onSelectCollection: (Long?) -> Unit,
): PaletteProvider =
    PaletteProvider { _ ->
        listOf(PaletteHit(category = "Actions", title = "Collection: All", run = { onSelectCollection(null) })) +
                collections.map { collection ->
                    PaletteHit(
                        category = "Actions",
                        title = "Collection: ${collection.name}",
                        run = { onSelectCollection(collection.id) },
                    )
                }
    }

private fun settingsProvider(navigate: (Screen) -> Unit): PaletteProvider = PaletteProvider { _ ->
    SETTINGS_ENTRIES.map { title ->
        PaletteHit(category = "Settings", title = "Setting: $title", run = { navigate(Screen.Settings) })
    }
}

private fun libraryProvider(library: LibraryRepository, navigate: (Screen) -> Unit): PaletteProvider =
    PaletteProvider { _ ->
        library.observeLibrary().first().map { item ->
            PaletteHit(
                category = "Manhwa",
                title = item.entry.title,
                subtitle = item.entry.sourceId,
                run = { navigate(Screen.Details(item.entry.sourceId, item.entry.mangaId)) },
            )
        }
    }

fun paletteTabs(
    library: LibraryRepository,
    navigate: (Screen) -> Unit,
    theme: MangoTheme,
    onThemeChange: (MangoTheme) -> Unit,
    themes: List<MangoTheme> = listOf(MangoDark),
    onToggleSidebar: () -> Unit = {},
    onToggleLibraryView: () -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    onClearSearchHistory: () -> Unit = {},
    collections: List<CollectionInfo> = emptyList(),
    onSelectCollection: (Long?) -> Unit = {},
    onManageCollections: () -> Unit = {},
): List<PaletteTab> {
    val screens = screenProvider(navigate)
    val accents = accentProvider(theme, onThemeChange)
    val themeHits = themeProvider(themes, onThemeChange)
    val manhwa = libraryProvider(library, navigate)
    val settings = settingsProvider(navigate)
    val actions = actionsProvider(
        onToggleSidebar,
        onToggleLibraryView,
        onCheckForUpdates,
        onClearSearchHistory,
        onManageCollections
    )
    val shelves = collectionsProvider(collections, onSelectCollection)
    return listOf(
        PaletteTab("All", listOf(screens, accents, themeHits, manhwa, settings, actions, shelves)),
        PaletteTab("Manhwa", listOf(manhwa)),
        PaletteTab("Actions", listOf(screens, accents, themeHits, settings, actions, shelves)),
    )
}
