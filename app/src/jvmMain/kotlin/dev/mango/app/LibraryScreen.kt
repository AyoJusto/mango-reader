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
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal const val LIBRARY_VIEW_GRID = "grid"
internal const val LIBRARY_VIEW_LIST = "list"

internal const val LIBRARY_LIST_TEST_TAG = "library-list"

internal const val LIBRARY_GRID_TEST_TAG = "library-grid"

/** Null when the chapter count is unknown. */
private fun LibraryItem.readFraction(): Float? =
    if (chapterCount > 0) (chapterCount - unreadCount).toFloat() / chapterCount else null

private fun LibraryItem.isFinished(): Boolean = chapterCount > 0 && unreadCount == 0

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
                    if (checkedAt != null) {
                        Text(
                            text = "checked ${formatRelativeTime(Instant.fromEpochMilliseconds(checkedAt), now)}",
                            style = MangoType.caption,
                            color = theme.textTertiary,
                            modifier = Modifier.padding(end = MangoSpace.sm),
                        )
                    }
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
                            CoverCard(
                                title = item.entry.title,
                                coverUrl = item.entry.cover,
                                sourceId = item.entry.sourceId,
                                mangaId = item.entry.mangaId,
                                unreadCount = item.unreadCount,
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

@Composable
private fun LibraryListRow(item: LibraryItem, onClick: () -> Unit) {
    val theme = LocalMangoTheme.current
    val hover = rememberHoverFill(rest = theme.bg1.copy(alpha = 0f), hover = theme.bg1)
    val fraction = item.readFraction()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .keyboardFocusRing(RoundedCornerShape(MangoRadius.control))
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
                .sharedCover(item.entry.sourceId, item.entry.mangaId)
                .testTag("cover:${item.entry.sourceId}/${item.entry.mangaId}")
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
            if (item.isFinished()) {
                Text(
                    text = "Completed",
                    style = MangoType.meta,
                    color = theme.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ProgressTrack(progress = fraction ?: 0f, modifier = Modifier.weight(1f))
        Text(
            text = item.lastReadAt?.let { formatDate(it) } ?: "—",
            style = MangoType.caption,
            color = theme.textTertiary,
            modifier = Modifier.width(110.dp),
            textAlign = TextAlign.End,
        )
    }
}

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

/** Counts always use the full items list, not the selected filter. */
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

@Composable
private fun CollectionChip(text: String, active: Boolean, onClick: () -> Unit) {
    val theme = LocalMangoTheme.current
    val hover = rememberHoverFill(
        rest = if (active) theme.accent else theme.bg2.copy(alpha = 0f),
        hover = if (active) theme.accent else theme.bg2,
    )
    Box(
        modifier = Modifier
            .keyboardFocusRing(RoundedCornerShape(MangoRadius.pill))
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
