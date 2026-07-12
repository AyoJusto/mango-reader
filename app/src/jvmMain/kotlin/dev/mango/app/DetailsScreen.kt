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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.mango.core.domain.CatalogCache
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.ChallengeRequiredException
import dev.mango.core.domain.ChallengeSolver
import dev.mango.core.domain.Download
import dev.mango.core.domain.DownloadManager
import dev.mango.core.domain.DownloadStatus
import dev.mango.core.domain.LibraryRepository
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.ReadProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Left column width shared by the loaded layout and its skeleton, so they can't drift apart. */
private val DETAILS_COVER_COLUMN_WIDTH = 300.dp

/** Screen edge padding shared by the loaded layout and its skeleton, so they can't drift apart. */
private val DETAILS_PAGE_PADDING = 48.dp

/** Pure, data-driven content — the screenshot harness renders this directly. */
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
    onToggleLibrary: () -> Unit,
    onOpenChapter: (Chapter, List<Chapter>) -> Unit,
    onDownloadChapter: (MangaEntry, Chapter) -> Unit,
    onDownloadAll: (MangaEntry, List<Chapter>) -> Unit,
    onClearStorage: () -> Unit = {},
    onMarkAllFinished: () -> Unit = {},
) {
    val theme = LocalMangoTheme.current
    // Local UI state for the range dialog — presentation-only, never leaves this composable.
    var showRangeDialog by remember { mutableStateOf(false) }
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }
    var showClearStorageDialog by remember { mutableStateOf(false) }
    val descendingChapters = remember(chapters) { chapters.sortedByDescending { it.number } }
    // Chapters already moving through the queue must not be re-enqueued by the bulk actions.
    val activeDownloadIds = remember(downloadsByChapterId) {
        downloadsByChapterId.filterValues {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING
        }.keys
    }
    val continueTarget = remember(chapters, latestProgress) { continueTarget(chapters, latestProgress) }
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
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .shadow(elevation = 16.dp, shape = RoundedCornerShape(MangoRadius.panel))
                            .clip(RoundedCornerShape(MangoRadius.panel))
                            .background(theme.bg2),
                    ) {
                        val cover = details.entry.cover
                        if (cover != null) {
                            AsyncImage(
                                model = cover,
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
                    KitButton(
                        label = if (inLibrary) "In library — remove" else "Add to library",
                        onClick = onToggleLibrary,
                        style = KitButtonStyle.SECONDARY,
                        modifier = Modifier.fillMaxWidth(),
                    )
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
                        onClick = { showRangeDialog = true },
                        style = KitButtonStyle.GHOST,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (hasDownloads) {
                        KitButton(
                            label = "Clear storage",
                            onClick = { showClearStorageDialog = true },
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
                Column(modifier = Modifier.weight(1f)) {
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
                    Text(
                        text = "${chapters.size} chapters",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.textPrimary,
                    )
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
                                onOpen = { onOpenChapter(chapter, chapters) },
                                onDownload = { onDownloadChapter(details.entry, chapter) },
                            )
                        }
                    }
                }
            }
        }
    }
    if (showRangeDialog) {
        val from = fromText.toDoubleOrNull()
        val to = toText.toDoubleOrNull()
        AlertDialog(
            onDismissRequest = { showRangeDialog = false },
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
                            // normalize: From=10 To=5 means 5..10, not an empty range that
                            // silently downloads nothing
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

/** One key/value line of the left column's metadata list; [accentValue] renders the value in accent. */
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

/**
 * A genre tag chip — display only, not a filter control. Kit's [Pill] hardcodes 8/3dp padding
 * and an 11sp label, which don't match this chip's 11/3dp padding and 12sp label, so this stays
 * its own composable rather than a [Pill] call; it still shares [MangoRadius.pill] for the
 * corner radius.
 */
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

/**
 * One chapter row: state dot, mono chapter number, title, state text, date, and the
 * per-chapter download action. [inProgress] is non-null only for the chapter the reader
 * last left unfinished — the one row that reads in accent.
 */
@Composable
private fun ChapterRow(
    chapter: Chapter,
    finished: Boolean,
    downloaded: Boolean,
    download: Download?,
    inProgress: ReadProgress?,
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
            text = chapter.title ?: "Chapter ${formatChapterNumber(chapter.number)}",
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            color = if (finished) theme.textSecondary else theme.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            // Chapter page counts aren't loaded on this screen, so the in-progress state
            // shows the saved page (the Continue button's grammar), not a percentage.
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
            // A queued chapter has no action: it is already on its way, and re-enqueueing is
            // the only thing the glyph could do.
            download?.status == DownloadStatus.QUEUED -> Spacer(modifier = Modifier.size(12.dp))
            else -> ChapterDownloadGlyph(onClick = onDownload)
        }
    }
}

/** The per-chapter download affordance — a nested click target that must not trigger the row's open. */
@Composable
private fun ChapterDownloadGlyph(onClick: () -> Unit) {
    val theme = LocalMangoTheme.current
    val hover = rememberHoverFill(rest = theme.surface.copy(alpha = 0f), hover = theme.surface)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(MangoRadius.keycap))
            .background(hover.fill)
            .hoverable(hover.interaction)
            .clickable(interactionSource = hover.interaction, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = "↓", style = MangoType.caption, color = theme.textSecondary)
    }
}

/** Loading placeholder whose skeleton shapes mirror the loaded two-column layout, so content replaces it without a jump. */
@Composable
private fun DetailsSkeleton() {
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
                            modifier = Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(MangoRadius.control)),
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MangoSpace.sm),
                ) {
                    SkeletonBlock(modifier = Modifier.width(320.dp).height(34.dp).clip(RoundedCornerShape(MangoRadius.control)))
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
                            modifier = Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(MangoRadius.control)),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The chapter the Continue action resumes into, plus its button label — computed in one place
 * so the header button and its click handler can't drift. Walks chapters in ascending order
 * (unlike the chapters list, which shows newest-first) so "next after latest" walks forward.
 * Null when there is nothing to open (no chapters, or the latest chapter is finished and last).
 */
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

/**
 * Stateful loader: paints the persisted [catalogCache] entry immediately when present, then
 * always revalidates live and tracks library membership. With [autoContinue] set, fires the
 * Continue action exactly once when the chapter list has loaded and the latest-progress chapter
 * is available in it.
 */
@Composable
fun DetailsScreen(
    sourceId: String,
    mangaId: String,
    catalog: CatalogRepository,
    library: LibraryRepository,
    downloads: DownloadManager,
    challengeSolver: ChallengeSolver,
    catalogCache: CatalogCache,
    onOpenChapter: (Chapter, List<Chapter>) -> Unit,
    onDownloadChapter: (MangaEntry, Chapter) -> Unit = { _, _ -> },
    onDownloadAll: (MangaEntry, List<Chapter>) -> Unit = { _, _ -> },
    autoContinue: Boolean = false,
) {
    val theme = LocalMangoTheme.current
    var details by remember(sourceId, mangaId) { mutableStateOf<MangaDetails?>(null) }
    var chapters by remember(sourceId, mangaId) { mutableStateOf<List<Chapter>>(emptyList()) }
    var finishedChapterIds by remember(sourceId, mangaId) { mutableStateOf<Set<String>>(emptySet()) }
    var latestProgress by remember(sourceId, mangaId) { mutableStateOf<ReadProgress?>(null) }
    var error by remember(sourceId, mangaId) { mutableStateOf<String?>(null) }
    var challengeUrl by remember(sourceId, mangaId) { mutableStateOf<String?>(null) }
    var solving by remember(sourceId, mangaId) { mutableStateOf(false) }
    var reloadKey by remember(sourceId, mangaId) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val libraryItems by library.observeLibrary().collectAsState(initial = emptyList())
    val mangaDownloads by remember(sourceId, mangaId) {
        downloads.observeDownloads().map { rows -> rows.filter { it.sourceId == sourceId && it.mangaId == mangaId } }
    }.collectAsState(initial = emptyList())
    val downloadedChapterIds = mangaDownloads.filter { it.status == DownloadStatus.DONE }.map { it.chapterId }.toSet()
    val downloadsByChapterId = mangaDownloads.associateBy { it.chapterId }
    val hasDownloads = mangaDownloads.isNotEmpty()
    val inLibrary = libraryItems.any { it.entry.sourceId == sourceId && it.entry.mangaId == mangaId }

    // True once a usable chapter list + progress pair is in state: immediately after a cache
    // hit, or after the live fetch resolves (either way) on a cold open. The auto-continue
    // fallback below must not conclude "nothing to continue into" before this point.
    var chaptersSettled by remember(sourceId, mangaId) { mutableStateOf(false) }
    LaunchedEffect(sourceId, mangaId, reloadKey) {
        error = null
        challengeUrl = null
        val cached = catalogCache.get(sourceId, mangaId)
        if (cached != null) {
            details = cached.details
            chapters = cached.chapters
            // Unconditional: a manga not in the library has no library_item row, so the UPDATE
            // simply no-ops rather than needing a membership check here.
            library.setChapterCount(sourceId, mangaId, chapters.size)
            finishedChapterIds = library.finishedChapterIds(sourceId, mangaId)
            latestProgress = library.latestProgress(sourceId, mangaId)
            chaptersSettled = true
        }
        // Always revalidate live. A cached copy already on screen makes failure here silent —
        // the stale render stays instead of being replaced by an error or challenge card. Once
        // a manga is cached, no Details open surfaces a challenge for it; the Reader's Solve
        // button (live page fetches still throw there) is the path that refreshes clearance.
        try {
            val loadedDetails = catalog.details(sourceId, mangaId)
            val loadedChapters = catalog.chapters(sourceId, mangaId)
            catalogCache.put(sourceId, mangaId, loadedDetails, loadedChapters)
            details = loadedDetails
            chapters = loadedChapters
            // Unconditional: revalidation may have changed the chapter count.
            library.setChapterCount(sourceId, mangaId, chapters.size)
            if (cached == null) {
                finishedChapterIds = library.finishedChapterIds(sourceId, mangaId)
                latestProgress = library.latestProgress(sourceId, mangaId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChallengeRequiredException) {
            if (cached == null) {
                error = "This source is protected by Cloudflare"
                challengeUrl = e.url
            }
        } catch (e: Exception) {
            if (cached == null) {
                error = e.message ?: "Failed to load"
            }
        }
        chaptersSettled = true
    }

    // While an auto-continue is pending, Details acts as a headless loader: a neutral veil
    // renders instead of the screen, so the pass-through reads sidebar -> reader with no
    // intermediate content flash. The veil drops only when continuing turns out impossible
    // (no progress row, its chapter gone from the list, or nothing after the latest chapter),
    // decided strictly after [chaptersSettled] so a slow load isn't mistaken for a dead end.
    val passThrough = remember(sourceId, mangaId) { mutableStateOf(autoContinue) }
    // Exactly-once guard: recompositions and progress refreshes after the jump must not
    // re-fire the auto-continue, or backing out of the Reader would bounce straight back in.
    val autoContinueFired = remember { mutableStateOf(false) }
    LaunchedEffect(autoContinue, chapters, latestProgress, chaptersSettled) {
        if (!autoContinue || autoContinueFired.value) return@LaunchedEffect
        val progress = latestProgress
        val target = progress
            ?.takeIf { p -> chapters.any { it.chapterId == p.chapterId } }
            ?.let { continueTarget(chapters, it) }
        when {
            target != null -> {
                autoContinueFired.value = true
                onOpenChapter(target.first, chapters)
            }
            chaptersSettled -> passThrough.value = false
        }
    }

    val currentDetails = details
    val currentError = error
    when {
        currentError != null -> Surface(modifier = Modifier.fillMaxSize(), color = theme.bg0) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val url = challengeUrl
                ChallengeErrorContent(
                    error = currentError,
                    challengeUrl = url,
                    solving = solving,
                    solveEnabled = !solving,
                    onSolveChallenge = {
                        if (url != null) {
                            scope.launch {
                                solving = true
                                try {
                                    if (challengeSolver.solve(sourceId, url)) reloadKey++
                                } finally {
                                    solving = false
                                }
                            }
                        }
                    },
                )
            }
        }
        passThrough.value -> Surface(modifier = Modifier.fillMaxSize(), color = theme.bg0) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        currentDetails == null -> DetailsSkeleton()
        else -> DetailsScreenContent(
            details = currentDetails,
            chapters = chapters,
            inLibrary = inLibrary,
            finishedChapterIds = finishedChapterIds,
            downloadedChapterIds = downloadedChapterIds,
            downloadsByChapterId = downloadsByChapterId,
            hasDownloads = hasDownloads,
            latestProgress = latestProgress,
            onToggleLibrary = {
                scope.launch {
                    if (inLibrary) {
                        library.removeFromLibrary(sourceId, mangaId)
                    } else {
                        library.addToLibrary(currentDetails.entry)
                        // The freshly inserted row starts at chapter_count 0; the loaded
                        // chapter list is at hand, so the unread badge is right immediately
                        // instead of after the next Details visit.
                        library.setChapterCount(sourceId, mangaId, chapters.size)
                    }
                }
            },
            onOpenChapter = onOpenChapter,
            onDownloadChapter = onDownloadChapter,
            onDownloadAll = onDownloadAll,
            onClearStorage = { scope.launch { downloads.clearDownloads(sourceId, mangaId) } },
            onMarkAllFinished = {
                scope.launch {
                    // ponytail: one progress upsert per chapter — fine at chapter-list scale
                    // (hundreds of rows); a repository-level bulk mark-finished is the upgrade
                    // path if it ever drags.
                    chapters.forEach { chapter ->
                        library.setProgress(
                            sourceId,
                            mangaId,
                            chapter.chapterId,
                            page = 0,
                            finished = true,
                            chapterNumber = chapter.number,
                        )
                    }
                    // Re-read the same state the load effect populates, so the rows and the
                    // Continue button reflect the new progress without renavigation.
                    finishedChapterIds = library.finishedChapterIds(sourceId, mangaId)
                    latestProgress = library.latestProgress(sourceId, mangaId)
                }
            },
        )
    }
}
