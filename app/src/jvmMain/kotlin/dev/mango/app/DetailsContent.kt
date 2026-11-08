package dev.mango.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.CollectionInfo
import dev.mango.core.domain.Download
import dev.mango.core.domain.DownloadStatus
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.ReadProgress
import kotlin.time.Clock
import kotlin.time.Instant

private val DETAILS_COVER_COLUMN_WIDTH = 300.dp

private val DETAILS_PAGE_PADDING = 48.dp

@Composable
fun DetailsScreenContent(
    details: MangaDetails,
    chapters: List<Chapter>,
    inLibrary: Boolean,
    finishedChapterIds: Set<String> = emptySet(),
    downloadedChapterIds: Set<String> = emptySet(),
    downloadsByChapterId: Map<String, Download> = emptyMap(),
    hasDownloads: Boolean = false,
    latestProgress: ReadProgress? = null,
    newChapterIds: Set<String> = emptySet(),
    checkedAt: Instant? = null,
    revalidating: Boolean = false,
    collections: List<CollectionInfo> = emptyList(),
    memberCollectionIds: Set<Long> = emptySet(),
    onAddToLibrary: () -> Unit = {},
    onRemoveFromLibrary: () -> Unit = {},
    onSetMembership: (Set<Long>) -> Unit = {},
    onCreateCollection: suspend (String) -> Long = { 0L },
    onOpenChapter: (Chapter, List<Chapter>) -> Unit,
    onDownloadChapter: (MangaEntry, Chapter) -> Unit,
    onDownloadAll: (MangaEntry, List<Chapter>) -> Unit,
    onClearStorage: () -> Unit = {},
    onMarkAllFinished: () -> Unit = {},
    onRefresh: () -> Unit = {},
) {
    val theme = LocalMangoTheme.current
    var showRangeDialog by remember { mutableStateOf(false) }
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }
    var showClearStorageDialog by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    val toastState = remember { ToastState() }
    val descendingChapters = remember(chapters) { chapters.sortedByDescending { it.number } }
    val activeDownloadIds = remember(downloadsByChapterId) {
        downloadsByChapterId.filterValues {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING
        }.keys
    }
    val continueTarget = remember(chapters, latestProgress) { continueTarget(chapters, latestProgress) }
    Surface(modifier = Modifier.fillMaxSize(), color = theme.bg0) {
        Box(modifier = Modifier.fillMaxSize()) {
            ContentColumn(max = MangoSpace.gridMaxWidth) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(
                        start = DETAILS_PAGE_PADDING,
                        end = DETAILS_PAGE_PADDING,
                        top = DETAILS_PAGE_PADDING,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                ) {
                    DetailsSidebarColumn(
                        modifier = Modifier.width(DETAILS_COVER_COLUMN_WIDTH),
                        details = details,
                        chapters = chapters,
                        continueTarget = continueTarget,
                        inLibrary = inLibrary,
                        collections = collections,
                        memberCollectionIds = memberCollectionIds,
                        showPicker = showPicker,
                        onShowPickerChange = { showPicker = it },
                        toastState = toastState,
                        finishedChapterIds = finishedChapterIds,
                        downloadedChapterIds = downloadedChapterIds,
                        activeDownloadIds = activeDownloadIds,
                        hasDownloads = hasDownloads,
                        onOpenChapter = onOpenChapter,
                        onMarkAllFinished = onMarkAllFinished,
                        onAddToLibrary = onAddToLibrary,
                        onRemoveFromLibrary = onRemoveFromLibrary,
                        onSetMembership = onSetMembership,
                        onCreateCollection = onCreateCollection,
                        onDownloadAll = onDownloadAll,
                        onDownloadRangeClick = { showRangeDialog = true },
                        onClearStorageClick = { showClearStorageDialog = true },
                    )
                    DetailsChapterListColumn(
                        modifier = Modifier.weight(1f),
                        details = details,
                        chapters = chapters,
                        descendingChapters = descendingChapters,
                        checkedAt = checkedAt,
                        revalidating = revalidating,
                        finishedChapterIds = finishedChapterIds,
                        downloadedChapterIds = downloadedChapterIds,
                        downloadsByChapterId = downloadsByChapterId,
                        latestProgress = latestProgress,
                        newChapterIds = newChapterIds,
                        onRefresh = onRefresh,
                        onOpenChapter = onOpenChapter,
                        onDownloadChapter = onDownloadChapter,
                    )
                }
            }
            Toast(state = toastState)
        }
    }
    if (showRangeDialog) {
        val from = fromText.toDoubleOrNull()
        val to = toText.toDoubleOrNull()
        AlertDialog(
            onDismissRequest = { showRangeDialog = false },
            modifier = Modifier.sideButtons { showRangeDialog = false },
            title = { Text("Download range") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = fromText,
                        onValueChange = { fromText = it },
                        label = { Text("From") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = toText,
                        onValueChange = { toText = it },
                        label = { Text("To") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (from != null && to != null) {
                            // Normalize From > To into a range instead of silently downloading nothing.
                            val range = minOf(from, to)..maxOf(from, to)
                            onDownloadAll(
                                details.entry,
                                chapters.filter {
                                    it.number in range &&
                                            it.chapterId !in downloadedChapterIds &&
                                            it.chapterId !in activeDownloadIds
                                },
                            )
                            showRangeDialog = false
                        }
                    },
                    enabled = from != null && to != null,
                ) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { showRangeDialog = false }) { Text("Cancel") }
            },
        )
    }
    if (showClearStorageDialog) {
        AlertDialog(
            onDismissRequest = { showClearStorageDialog = false },
            modifier = Modifier.sideButtons { showClearStorageDialog = false },
            title = { Text("Clear storage") },
            text = { Text("Delete all downloaded chapters of this series from disk?") },
            confirmButton = {
                TextButton(onClick = {
                    onClearStorage()
                    showClearStorageDialog = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearStorageDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DetailsSidebarColumn(
    modifier: Modifier,
    details: MangaDetails,
    chapters: List<Chapter>,
    continueTarget: Pair<Chapter, String>?,
    inLibrary: Boolean,
    collections: List<CollectionInfo>,
    memberCollectionIds: Set<Long>,
    showPicker: Boolean,
    onShowPickerChange: (Boolean) -> Unit,
    toastState: ToastState,
    finishedChapterIds: Set<String>,
    downloadedChapterIds: Set<String>,
    activeDownloadIds: Set<String>,
    hasDownloads: Boolean,
    onOpenChapter: (Chapter, List<Chapter>) -> Unit,
    onMarkAllFinished: () -> Unit,
    onAddToLibrary: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onSetMembership: (Set<Long>) -> Unit,
    onCreateCollection: suspend (String) -> Long,
    onDownloadAll: (MangaEntry, List<Chapter>) -> Unit,
    onDownloadRangeClick: () -> Unit,
    onClearStorageClick: () -> Unit,
) {
    val theme = LocalMangoTheme.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MangoSpace.sm),
    ) {
        Box(
            modifier = Modifier
                .sharedCover(details.entry.sourceId, details.entry.mangaId)
                .testTag("detailsCover")
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .shadow(elevation = 16.dp, shape = RoundedCornerShape(MangoRadius.panel))
                .clip(RoundedCornerShape(MangoRadius.panel))
                .background(theme.bg2),
        ) {
            val cover = details.entry.cover
            if (cover != null) {
                AsyncImage(
                    model = rememberCoverRequest(details.entry.sourceId, cover),
                    contentDescription = details.entry.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        continueTarget?.let { (chapter, label) ->
            KitButton(
                label = label,
                onClick = { onOpenChapter(chapter, chapters) },
                style = KitButtonStyle.PRIMARY,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (chapters.isNotEmpty()) {
            KitButton(
                label = "Mark finished",
                onClick = onMarkAllFinished,
                style = KitButtonStyle.SECONDARY,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            SplitButton(
                label = if (inLibrary) "In library ✓" else "Add to library",
                onMainClick = {
                    if (inLibrary) {
                        onShowPickerChange(true)
                    } else {
                        onAddToLibrary()
                        val defaultName = collections.firstOrNull { it.isDefault }?.name ?: "library"
                        toastState.show(
                            message = "Added to $defaultName",
                            actionLabel = "Change",
                            onAction = { onShowPickerChange(true) },
                        )
                    }
                },
                onArrowClick = { onShowPickerChange(true) },
                modifier = Modifier.fillMaxWidth(),
            )
            // Local set so a rapid second toggle doesn't start from a stale flow snapshot.
            var checkedIds by remember(showPicker) { mutableStateOf(memberCollectionIds) }
            AddToCollectionsPicker(
                expanded = showPicker,
                onDismissRequest = { onShowPickerChange(false) },
                rows = collections.sortedBy { it.position }.map { collection ->
                    CollectionCheckboxRow(
                        id = collection.id,
                        name = collection.name,
                        isDefault = collection.isDefault,
                        checked = collection.id in checkedIds,
                    )
                },
                onToggle = { id ->
                    val newIds = if (id in checkedIds) checkedIds - id else checkedIds + id
                    checkedIds = newIds
                    onSetMembership(newIds)
                },
                onCreateAndFile = { name ->
                    val newId = onCreateCollection(name)
                    val newIds = checkedIds + newId
                    checkedIds = newIds
                    onSetMembership(newIds)
                },
                inLibrary = inLibrary,
                onRemoveFromLibrary = {
                    onRemoveFromLibrary()
                    onShowPickerChange(false)
                },
            )
        }
        KitButton(
            label = "Download all",
            onClick = {
                onDownloadAll(
                    details.entry,
                    chapters.filter { it.chapterId !in downloadedChapterIds && it.chapterId !in activeDownloadIds },
                )
            },
            style = KitButtonStyle.GHOST,
            modifier = Modifier.fillMaxWidth(),
        )
        KitButton(
            label = "Download unread",
            onClick = {
                onDownloadAll(
                    details.entry,
                    chapters.filter {
                        it.chapterId !in finishedChapterIds &&
                                it.chapterId !in downloadedChapterIds &&
                                it.chapterId !in activeDownloadIds
                    },
                )
            },
            style = KitButtonStyle.GHOST,
            modifier = Modifier.fillMaxWidth(),
        )
        KitButton(
            label = "Download range…",
            onClick = onDownloadRangeClick,
            style = KitButtonStyle.GHOST,
            modifier = Modifier.fillMaxWidth(),
        )
        if (hasDownloads) {
            KitButton(
                label = "Clear storage",
                onClick = onClearStorageClick,
                style = KitButtonStyle.GHOST,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(MangoSpace.base))
        MetadataRow(key = "Status", value = details.status.name)
        if (details.authors.isNotEmpty()) {
            MetadataRow(key = "Author", value = details.authors.joinToString(", "))
        }
        MetadataRow(key = "Source", value = details.entry.sourceId)
        if (chapters.isNotEmpty()) {
            MetadataRow(
                key = "Progress",
                value = "${finishedChapterIds.size} of ${chapters.size} read",
                accentValue = true,
            )
        }
    }
}

@Composable
private fun DetailsChapterListColumn(
    modifier: Modifier,
    details: MangaDetails,
    chapters: List<Chapter>,
    descendingChapters: List<Chapter>,
    checkedAt: Instant?,
    revalidating: Boolean,
    finishedChapterIds: Set<String>,
    downloadedChapterIds: Set<String>,
    downloadsByChapterId: Map<String, Download>,
    latestProgress: ReadProgress?,
    newChapterIds: Set<String>,
    onRefresh: () -> Unit,
    onOpenChapter: (Chapter, List<Chapter>) -> Unit,
    onDownloadChapter: (MangaEntry, Chapter) -> Unit,
) {
    val theme = LocalMangoTheme.current
    Column(modifier = modifier) {
        Text(
            text = details.entry.title,
            style = MangoType.display,
            color = theme.textPrimary,
        )
        if (details.tags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(MangoSpace.sm))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MangoSpace.xs),
                verticalArrangement = Arrangement.spacedBy(MangoSpace.xs),
            ) {
                details.tags.forEach { tag -> GenreChip(tag) }
            }
        }
        details.description?.let { description ->
            Spacer(modifier = Modifier.height(MangoSpace.md))
            Text(
                text = description,
                fontSize = 14.sp,
                lineHeight = 22.4.sp,
                color = theme.textSecondary,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 680.dp),
            )
        }
        Spacer(modifier = Modifier.height(MangoSpace.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${chapters.size} chapters",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = theme.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (checkedAt != null) {
                Text(
                    text = "checked ${formatRelativeTime(checkedAt, Clock.System.now())}",
                    fontSize = 12.5.sp,
                    color = theme.textTertiary,
                )
            }
            RefreshGlyphButton(
                checking = revalidating,
                onClick = onRefresh,
                fill = theme.surface,
                hoverFill = theme.bg2,
                testTag = "details-refresh",
                modifier = Modifier.padding(start = MangoSpace.sm),
            )
        }
        Spacer(modifier = Modifier.height(MangoSpace.xs))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = MangoSpace.xl),
        ) {
            items(descendingChapters, key = { it.chapterId }) { chapter ->
                ChapterRow(
                    chapter = chapter,
                    finished = chapter.chapterId in finishedChapterIds,
                    downloaded = chapter.chapterId in downloadedChapterIds,
                    download = downloadsByChapterId[chapter.chapterId],
                    inProgress = latestProgress?.takeIf { it.chapterId == chapter.chapterId && !it.finished },
                    isNew = chapter.chapterId in newChapterIds,
                    onOpen = { onOpenChapter(chapter, chapters) },
                    onDownload = { onDownloadChapter(details.entry, chapter) },
                )
            }
        }
    }
}

@Composable
private fun MetadataRow(key: String, value: String, accentValue: Boolean = false) {
    val theme = LocalMangoTheme.current
    Row {
        Text(text = key, fontSize = 12.5.sp, color = theme.textTertiary, modifier = Modifier.width(80.dp))
        Text(
            text = value,
            fontSize = 12.5.sp,
            color = if (accentValue) theme.accent else theme.textSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Not a Pill call: Pill's hardcoded padding and 11sp label don't match this chip. */
@Composable
private fun GenreChip(text: String) {
    val theme = LocalMangoTheme.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(MangoRadius.pill))
            .background(theme.bg2)
            .padding(horizontal = 11.dp, vertical = 3.dp),
    ) {
        Text(text = text, style = MangoType.caption, color = theme.textSecondary)
    }
}

@Composable
private fun ChapterRow(
    chapter: Chapter,
    finished: Boolean,
    downloaded: Boolean,
    download: Download?,
    inProgress: ReadProgress?,
    isNew: Boolean = false,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
) {
    val theme = LocalMangoTheme.current
    val hover = rememberHoverFill(rest = theme.bg1.copy(alpha = 0f), hover = theme.bg1)
    val dotColor = when {
        inProgress != null -> theme.accent
        finished -> theme.textPrimary.copy(alpha = 0.25f)
        else -> theme.textPrimary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .keyboardFocusRing(RoundedCornerShape(MangoRadius.control))
            .clip(RoundedCornerShape(MangoRadius.control))
            .background(hover.fill)
            .hoverable(hover.interaction)
            .clickable(interactionSource = hover.interaction, indication = null, onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm),
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Text(
            text = formatChapterNumber(chapter.number),
            style = MangoType.monoChapter,
            color = theme.textTertiary,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = realChapterTitle(chapter.number, chapter.title) ?: "",
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            color = if (finished) theme.textSecondary else theme.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isNew) {
            Box(
                modifier = Modifier
                    .background(theme.accent.copy(alpha = 0.16f), RoundedCornerShape(MangoRadius.pill))
                    .padding(horizontal = 7.dp, vertical = 1.5.dp),
            ) {
                Text(
                    text = "NEW",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.06.em,
                    color = theme.accent,
                )
            }
        }
        when {
            inProgress != null -> Text(
                text = "reading · p. ${inProgress.page + 1}",
                style = MangoType.caption,
                color = theme.accent,
            )

            download?.status == DownloadStatus.RUNNING && download.pagesTotal > 0 -> Text(
                text = "${download.pagesDone}/${download.pagesTotal}",
                style = MangoType.monoChapter,
                color = theme.accent,
            )

            download?.status == DownloadStatus.QUEUED -> Text(
                text = "queued",
                style = MangoType.caption,
                color = theme.textTertiary,
            )

            download?.status == DownloadStatus.FAILED -> Text(
                text = "failed",
                style = MangoType.caption,
                color = theme.danger,
            )

            downloaded && !finished -> Text(text = "downloaded", style = MangoType.caption, color = theme.success)
        }
        Text(
            text = chapter.publishedAt?.let { formatDate(it) }.orEmpty(),
            style = MangoType.caption,
            color = theme.textTertiary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(90.dp),
        )
        when {
            downloaded -> Text(text = "✓", style = MangoType.caption, color = theme.success)
            download?.status == DownloadStatus.RUNNING -> CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = theme.accent,
            )

            download?.status == DownloadStatus.QUEUED -> Spacer(modifier = Modifier.size(12.dp))
            else -> ChapterDownloadGlyph(onClick = onDownload)
        }
    }
}

/** Nested click target; must not trigger the row's open. */
@Composable
private fun ChapterDownloadGlyph(onClick: () -> Unit) {
    val theme = LocalMangoTheme.current
    val hover = rememberHoverFill(rest = theme.surface.copy(alpha = 0f), hover = theme.surface)
    Box(
        modifier = Modifier
            .keyboardFocusRing(RoundedCornerShape(MangoRadius.keycap))
            .clip(RoundedCornerShape(MangoRadius.keycap))
            .background(hover.fill)
            .hoverable(hover.interaction)
            .clickable(interactionSource = hover.interaction, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = "↓", style = MangoType.caption, color = theme.textSecondary)
    }
}

@Composable
internal fun DetailsSkeleton() {
    val theme = LocalMangoTheme.current
    Surface(modifier = Modifier.fillMaxSize(), color = theme.bg0) {
        ContentColumn(max = MangoSpace.gridMaxWidth) {
            Row(
                modifier = Modifier.fillMaxSize().padding(
                    start = DETAILS_PAGE_PADDING,
                    end = DETAILS_PAGE_PADDING,
                    top = DETAILS_PAGE_PADDING,
                ),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                Column(
                    modifier = Modifier.width(DETAILS_COVER_COLUMN_WIDTH),
                    verticalArrangement = Arrangement.spacedBy(MangoSpace.sm),
                ) {
                    SkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(MangoRadius.panel)),
                    )
                    repeat(2) {
                        SkeletonBlock(
                            modifier = Modifier.fillMaxWidth().height(38.dp)
                                .clip(RoundedCornerShape(MangoRadius.control)),
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MangoSpace.sm),
                ) {
                    SkeletonBlock(
                        modifier = Modifier.width(320.dp).height(34.dp).clip(RoundedCornerShape(MangoRadius.control))
                    )
                    SkeletonBlock(
                        modifier = Modifier
                            .widthIn(max = 680.dp)
                            .fillMaxWidth()
                            .height(64.dp)
                            .clip(RoundedCornerShape(MangoRadius.control)),
                    )
                    Spacer(modifier = Modifier.height(MangoSpace.xs))
                    repeat(6) {
                        SkeletonBlock(
                            modifier = Modifier.fillMaxWidth().height(36.dp)
                                .clip(RoundedCornerShape(MangoRadius.control)),
                        )
                    }
                }
            }
        }
    }
}

/** Null when there is nothing to open. */
internal fun continueTarget(chapters: List<Chapter>, latestProgress: ReadProgress?): Pair<Chapter, String>? {
    val ascending = chapters.sortedBy { it.number }
    val latestChapter = latestProgress?.let { progress -> ascending.find { it.chapterId == progress.chapterId } }
    return when {
        latestProgress == null || latestChapter == null -> ascending.firstOrNull()?.let { it to "Start reading" }
        !latestProgress.finished -> latestChapter to
                "Continue — Ch. ${formatChapterNumber(latestChapter.number)} · p. ${latestProgress.page + 1}"

        else -> {
            val next = ascending.getOrNull(ascending.indexOf(latestChapter) + 1)
            next?.let { it to "Continue — Ch. ${formatChapterNumber(it.number)}" }
        }
    }
}
