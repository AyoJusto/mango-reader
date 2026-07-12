package dev.mango.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.mango.core.domain.LibraryItem
import dev.mango.core.domain.LibraryRepository
import dev.mango.core.domain.MangaEntry
import kotlin.math.roundToInt

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
) {
    val theme = LocalMangoTheme.current
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
                        text = "${items.size} series",
                        style = MangoType.caption,
                        color = theme.textTertiary,
                        modifier = Modifier.padding(end = MangoSpace.sm),
                    )
                    SegmentedControl(
                        options = listOf("Grid", "List"),
                        selectedIndex = if (libraryView == LIBRARY_VIEW_LIST) 1 else 0,
                        onSelect = { index -> onLibraryViewChange(if (index == 0) LIBRARY_VIEW_GRID else LIBRARY_VIEW_LIST) },
                    )
                }
                when {
                    items.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                        items(items, key = { "${it.entry.sourceId}/${it.entry.mangaId}" }) { item ->
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
                        items(items, key = { "${it.entry.sourceId}/${it.entry.mangaId}" }) { item ->
                            // CoverCard itself checks `finished` before falling back to unreadCount
                            // for which pill to show, so unreadCount is passed unconditionally here.
                            CoverCard(
                                title = item.entry.title,
                                coverUrl = item.entry.cover,
                                metaLine = item.metaLine(),
                                unreadCount = item.unreadCount,
                                progress = item.readFraction(),
                                finished = item.isFinished(),
                                onClick = { onOpenDetails(item.entry) },
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
                    model = cover,
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

/** Stateful loader: wires [LibraryRepository.observeLibrary] into [LibraryScreenContent]. */
@Composable
fun LibraryScreen(
    library: LibraryRepository,
    libraryView: String = LIBRARY_VIEW_GRID,
    onLibraryViewChange: (String) -> Unit = {},
    onBrowse: () -> Unit = {},
    onOpenDetails: (MangaEntry) -> Unit,
) {
    val items by library.observeLibrary().collectAsState(initial = emptyList())
    LibraryScreenContent(
        items = items,
        libraryView = libraryView,
        onLibraryViewChange = onLibraryViewChange,
        onOpenDetails = onOpenDetails,
        onBrowse = onBrowse,
    )
}
