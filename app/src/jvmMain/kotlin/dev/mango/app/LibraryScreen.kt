package dev.mango.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.mango.core.domain.CollectionInfo
import dev.mango.core.domain.LibraryItem
import dev.mango.core.domain.LibraryRepository
import dev.mango.core.domain.MangaEntry
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Grid vs list mode, persisted by [Settings.libraryView] and mirrored in [SETTINGS_ENTRIES]. */
internal const val LIBRARY_VIEW_GRID = "grid"
internal const val LIBRARY_VIEW_LIST = "list"

/** Test hook: the list view's row container, present only while [LIBRARY_VIEW_LIST] is active. */
internal const val LIBRARY_LIST_TEST_TAG = "library-list"

/** Test hook: the grid view's cell container, present only while [LIBRARY_VIEW_GRID] is active. */
internal const val LIBRARY_GRID_TEST_TAG = "library-grid"

/** A series' read fraction in [0, 1], or null when its chapter count is unknown (never opened yet). */
private fun LibraryItem.readFraction(): Float? =
    if (chapterCount > 0) (chapterCount - unreadCount).toFloat() / chapterCount else null

/** "Ch. 142 · 72%" once the chapter count is known; blank otherwise — nothing to report yet. */
private fun LibraryItem.metaLine(): String {
    val fraction = readFraction() ?: return ""
    return "Ch. $chapterCount · ${(fraction * 100).roundToInt()}%"
}

/**
 * A series counts as finished, for the library grid/list, once every known chapter has been
 * started and there is at least one chapter to read — the same signal [readFraction] reaching
 * 1.0 represents, named for the cover card's finished treatment.
 */
private fun LibraryItem.isFinished(): Boolean = chapterCount > 0 && unreadCount == 0

/** Pure, data-driven content — the screenshot harness renders this directly. */
@Composable
fun LibraryScreenContent(
    items: List<LibraryItem>,
    libraryView: String = LIBRARY_VIEW_GRID,
    onLibraryViewChange: (String) -> Unit = {},
    onOpenDetails: (MangaEntry) -> Unit,
    onBrowse: () -> Unit = {},
    checkedAt: Long? = null,
    checking: Boolean = false,
    onCheckForUpdates: () -> Unit = {},
    collections: List<CollectionInfo> = emptyList(),
    selectedCollectionId: Long? = null,
    onSelectCollection: (Long?) -> Unit = {},
    onCreateCollection: suspend (String) -> Unit = {},
    onManageCollections: () -> Unit = {},
) {
    val theme = LocalMangoTheme.current
    val now = Clock.System.now()
    val visibleItems = if (selectedCollectionId == null) {
        items
    } else {
        items.filter { selectedCollectionId in it.collectionIds }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = theme.bg0) {
        ContentColumn(max = MangoSpace.gridMaxWidth) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MangoSpace.screenGutter, vertical = MangoSpace.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Library",
                        style = MangoType.display,
                        color = theme.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (checkedAt != null) {
                            "${items.size} series · checked ${
                                formatRelativeTime(
                                    Instant.fromEpochMilliseconds(checkedAt),
                                    now
                                )
                            }"
                        } else {
                            "${items.size} series"
                        },
                        style = MangoType.caption,
                        color = theme.textTertiary,
                        modifier = Modifier.padding(end = MangoSpace.sm),
                    )
                    RefreshGlyphButton(
                        checking = checking,
                        onClick = onCheckForUpdates,
                        fill = theme.bg2,
                        hoverFill = theme.surface,
                        testTag = "library-refresh",
                        modifier = Modifier.padding(end = MangoSpace.sm),
                    )
                    SegmentedControl(
                        options = listOf("Grid", "List"),
                        selectedIndex = if (libraryView == LIBRARY_VIEW_LIST) 1 else 0,
                        onSelect = { index -> onLibraryViewChange(if (index == 0) LIBRARY_VIEW_GRID else LIBRARY_VIEW_LIST) },
                    )
                }
                CollectionsChipRow(
                    allCount = items.size,
                    collections = collections,
                    items = items,
                    selectedCollectionId = selectedCollectionId,
                    onSelectCollection = onSelectCollection,
                    onCreateCollection = onCreateCollection,
                    onManageCollections = onManageCollections,
                )
                when {
                    visibleItems.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        EmptyState(
                            title = "Nothing here yet",
                            guidance = "Browse sources to add manhwa, or press Shift-Shift to search everywhere.",
                            ctaLabel = "Browse sources",
                            onCta = onBrowse,
                        )
                    }

                    libraryView == LIBRARY_VIEW_LIST -> LazyColumn(
                        contentPadding = PaddingValues(horizontal = MangoSpace.screenGutter, vertical = MangoSpace.sm),
                        modifier = Modifier.fillMaxSize().testTag(LIBRARY_LIST_TEST_TAG),
                    ) {
                        items(visibleItems, key = { "${it.entry.sourceId}/${it.entry.mangaId}" }) { item ->
                            LibraryListRow(item = item, onClick = { onOpenDetails(item.entry) })
                        }
                    }

                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 196.dp),
                        contentPadding = PaddingValues(horizontal = MangoSpace.screenGutter, vertical = MangoSpace.sm),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize().testTag(LIBRARY_GRID_TEST_TAG),
                    ) {
                        items(visibleItems, key = { "${it.entry.sourceId}/${it.entry.mangaId}" }) { item ->
                            // CoverCard itself checks `finished` before falling back to unreadCount
                            // for which pill to show, so unreadCount is passed unconditionally here.
                            CoverCard(
                                title = item.entry.title,
                                coverUrl = item.entry.cover,
                                sourceId = item.entry.sourceId,
                                metaLine = item.metaLine(),
                                unreadCount = item.unreadCount,
                                progress = item.readFraction(),
                                finished = item.isFinished(),
                                onClick = { onOpenDetails(item.entry) },
                                newCount = item.newCount,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One row of the Library's list view: a small cover thumb, title + meta, a flexible progress
 * track, the read percentage, and when the series was last read.
 */
@Composable
private fun LibraryListRow(item: LibraryItem, onClick: () -> Unit) {
    val theme = LocalMangoTheme.current
    val hover = rememberHoverFill(rest = theme.bg1.copy(alpha = 0f), hover = theme.bg1)
    val fraction = item.readFraction()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MangoRadius.control))
            .background(hover.fill)
            .hoverable(hover.interaction)
            .clickable(interactionSource = hover.interaction, indication = null, onClick = onClick)
            .padding(vertical = MangoSpace.xs, horizontal = MangoSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm),
    ) {
        Box(
            modifier = Modifier
                .size(width = 32.dp, height = 44.dp)
                .clip(RoundedCornerShape(MangoRadius.keycap))
                .background(theme.bg2),
        ) {
            val cover = item.entry.cover
            if (cover != null) {
                AsyncImage(
                    model = rememberCoverRequest(item.entry.sourceId, cover),
                    contentDescription = item.entry.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(modifier = Modifier.width(250.dp)) {
            Text(
                text = item.entry.title,
                style = MangoType.bodyStrong,
                color = theme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (item.isFinished()) "Completed" else item.metaLine(),
                style = MangoType.meta,
                color = theme.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ProgressTrack(progress = fraction ?: 0f, modifier = Modifier.weight(1f))
        Text(
            text = fraction?.let { "${(it * 100).roundToInt()}%" } ?: "—",
            style = MangoType.caption,
            color = theme.textSecondary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(36.dp),
        )
        Text(
            text = item.lastReadAt?.let { formatDate(it) } ?: "—",
            style = MangoType.caption,
            color = theme.textTertiary,
            modifier = Modifier.width(110.dp),
            textAlign = TextAlign.End,
        )
    }
}

/** Stateful loader: wires [LibraryRepository.observeLibrary]/[LibraryRepository.observeCollections] into [LibraryScreenContent]. */
@Composable
fun LibraryScreen(
    library: LibraryRepository,
    libraryView: String = LIBRARY_VIEW_GRID,
    onLibraryViewChange: (String) -> Unit = {},
    onBrowse: () -> Unit = {},
    onOpenDetails: (MangaEntry) -> Unit,
    checkedAt: Long? = null,
    checking: Boolean = false,
    onCheckForUpdates: () -> Unit = {},
    selectedCollectionId: Long? = null,
    onSelectCollection: (Long?) -> Unit = {},
    onManageCollections: () -> Unit = {},
) {
    val items by library.observeLibrary().collectAsState(initial = emptyList())
    val collections by library.observeCollections().collectAsState(initial = emptyList())
    LibraryScreenContent(
        items = items,
        libraryView = libraryView,
        onLibraryViewChange = onLibraryViewChange,
        onOpenDetails = onOpenDetails,
        onBrowse = onBrowse,
        checkedAt = checkedAt,
        checking = checking,
        onCheckForUpdates = onCheckForUpdates,
        collections = collections,
        selectedCollectionId = selectedCollectionId,
        onSelectCollection = onSelectCollection,
        onCreateCollection = { name -> library.createCollection(name) },
        onManageCollections = onManageCollections,
    )
}

/**
 * The library's shelf tabs: "All · N" first, then one chip per [collections] entry in position
 * order, a "＋" chip that swaps itself for an inline name field to create a shelf, and a "⋯"
 * affordance pushed to the far right for [onManageCollections]. Counts are always computed
 * against the full [items] list, not whatever [selectedCollectionId] currently filters to.
 */
@Composable
private fun CollectionsChipRow(
    allCount: Int,
    collections: List<CollectionInfo>,
    items: List<LibraryItem>,
    selectedCollectionId: Long?,
    onSelectCollection: (Long?) -> Unit,
    onCreateCollection: suspend (String) -> Unit,
    onManageCollections: () -> Unit,
) {
    var creatingChip by remember { mutableStateOf(false) }
    // ponytail: plain Row, no overflow scroll — revisit past ~8 shelves
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MangoSpace.screenGutter, vertical = MangoSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MangoSpace.base),
    ) {
        CollectionChip(
            text = "All · $allCount",
            active = selectedCollectionId == null,
            onClick = { onSelectCollection(null) },
        )
        collections.forEach { collection ->
            CollectionChip(
                text = "${collection.name} · ${items.count { collection.id in it.collectionIds }}",
                active = selectedCollectionId == collection.id,
                onClick = { onSelectCollection(collection.id) },
            )
        }
        if (creatingChip) {
            NewCollectionChip(onCreate = onCreateCollection, onClose = { creatingChip = false })
        } else {
            CollectionChip(text = "＋", active = false, onClick = { creatingChip = true })
        }
        Spacer(modifier = Modifier.weight(1f))
        CollectionChip(text = "⋯", active = false, onClick = onManageCollections)
    }
}

/** One chip in [CollectionsChipRow]: accent fill + accent-on text while active, else secondary text with a bg2 hover fill. */
@Composable
private fun CollectionChip(text: String, active: Boolean, onClick: () -> Unit) {
    val theme = LocalMangoTheme.current
    val hover = rememberHoverFill(
        rest = if (active) theme.accent else theme.bg2.copy(alpha = 0f),
        hover = if (active) theme.accent else theme.bg2,
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(MangoRadius.pill))
            .background(hover.fill)
            .hoverable(hover.interaction)
            .clickable(interactionSource = hover.interaction, indication = null, onClick = onClick)
            .padding(vertical = 5.dp, horizontal = 13.dp),
    ) {
        Text(
            text = text,
            style = MangoType.body,
            color = if (active) theme.accentOn else theme.textSecondary,
        )
    }
}

/**
 * The "＋" chip's in-place replacement while creating a shelf: a chip-shaped text field (same
 * radius/padding family as [CollectionChip]). Enter calls [onCreate]; a thrown duplicate-name
 * rejection renders as a caption under the field, which stays open so the name can be fixed.
 * Escape or losing focus (a click elsewhere) calls [onClose] without creating anything.
 */
@Composable
private fun NewCollectionChip(onCreate: suspend (String) -> Unit, onClose: () -> Unit) {
    val theme = LocalMangoTheme.current
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    fun commit() {
        val trimmed = draft.trim()
        if (trimmed.isEmpty()) {
            onClose()
            return
        }
        scope.launch {
            try {
                onCreate(trimmed)
                onClose()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Could not create collection"
            }
        }
    }

    Column {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(MangoRadius.pill))
                .background(theme.bg2)
                .padding(vertical = 5.dp, horizontal = 13.dp),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it; error = null },
                singleLine = true,
                textStyle = MangoType.body.copy(color = theme.textPrimary),
                cursorBrush = SolidColor(theme.accent),
                modifier = Modifier
                    .widthIn(min = 64.dp)
                    .focusRequester(focusRequester)
                    .inlineEditKeys(onCommit = ::commit, onCancel = onClose)
                    .cancelOnFocusLoss(onClose),
            )
        }
        error?.let { message -> Text(text = message, style = MangoType.caption, color = theme.danger) }
    }
}
