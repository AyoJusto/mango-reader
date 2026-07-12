package dev.mango.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.ChallengeRequiredException
import dev.mango.core.domain.ChallengeSolver
import dev.mango.core.domain.DownloadManager
import dev.mango.core.domain.DownloadStatus
import dev.mango.core.domain.LibraryRepository
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.ReadProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Pure, data-driven content — the screenshot harness renders this directly. */
@Composable
fun DetailsScreenContent(
    details: MangaDetails,
    chapters: List<Chapter>,
    inLibrary: Boolean,
    finishedChapterIds: Set<String> = emptySet(),
    downloadedChapterIds: Set<String> = emptySet(),
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
    val continueTarget = remember(chapters, latestProgress) { continueTarget(chapters, latestProgress) }
    Surface(modifier = Modifier.fillMaxSize(), color = theme.bg0) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Column(
                modifier = Modifier.width(300.dp),
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
                    onClick = { onDownloadAll(details.entry, chapters.filter { it.chapterId !in downloadedChapterIds }) },
                    style = KitButtonStyle.GHOST,
                    modifier = Modifier.fillMaxWidth(),
                )
                KitButton(
                    label = "Download unread",
                    onClick = {
                        onDownloadAll(
                            details.entry,
                            chapters.filter { it.chapterId !in finishedChapterIds && it.chapterId !in downloadedChapterIds },
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
                            inProgress = latestProgress?.takeIf { it.chapterId == chapter.chapterId && !it.finished },
                            onOpen = { onOpenChapter(chapter, chapters) },
                            onDownload = { onDownloadChapter(details.entry, chapter) },
                        )
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
                                chapters.filter { it.number in range && it.chapterId !in downloadedChapterIds },
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

/** A genre tag chip — display only, not a filter control. */
@Composable
private fun GenreChip(text: String) {
    val theme = LocalMangoTheme.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(theme.bg2)
            .padding(horizontal = 11.dp, vertical = 3.dp),
    ) {
        Text(text = text, fontSize = 12.sp, color = theme.textSecondary)
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
    inProgress: ReadProgress?,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
) {
    val theme = LocalMangoTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(
        // Same-color-at-zero-alpha rest state; see Chrome.kt's title-bar glyph for why.
        targetValue = if (hovered) theme.bg1 else theme.bg1.copy(alpha = 0f),
        animationSpec = tween(MangoMotion.HOVER_MS),
    )
    val dotColor = when {
        inProgress != null -> theme.accent
        finished -> theme.textPrimary.copy(alpha = 0.25f)
        else -> theme.textPrimary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MangoRadius.control))
            .background(fill)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
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
                fontSize = 12.sp,
                color = theme.accent,
            )
            downloaded && !finished -> Text(text = "downloaded", fontSize = 12.sp, color = theme.success)
        }
        Text(
            text = chapter.publishedAt?.let { formatDate(it) }.orEmpty(),
            fontSize = 12.sp,
            color = theme.textTertiary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(90.dp),
        )
        if (downloaded) {
            Text(text = "✓", fontSize = 12.sp, color = theme.success)
        } else {
            ChapterDownloadGlyph(onClick = onDownload)
        }
    }
}

/** The per-chapter download affordance — a nested click target that must not trigger the row's open. */
@Composable
private fun ChapterDownloadGlyph(onClick: () -> Unit) {
    val theme = LocalMangoTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(
        // Same-color-at-zero-alpha rest state; see Chrome.kt's title-bar glyph for why.
        targetValue = if (hovered) theme.surface else theme.surface.copy(alpha = 0f),
        animationSpec = tween(MangoMotion.HOVER_MS),
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(MangoRadius.keycap))
            .background(fill)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = "↓", fontSize = 12.sp, color = theme.textSecondary)
    }
}

/** Loading placeholder whose skeleton shapes mirror the loaded two-column layout, so content replaces it without a jump. */
@Composable
private fun DetailsSkeleton() {
    val theme = LocalMangoTheme.current
    Surface(modifier = Modifier.fillMaxSize(), color = theme.bg0) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Column(
                modifier = Modifier.width(300.dp),
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

/**
 * The chapter the Continue action resumes into, plus its button label — computed in one place
 * so the header button and its click handler can't drift. Walks chapters in ascending order
 * (unlike the chapters list, which shows newest-first) so "next after latest" walks forward.
 * Null when there is nothing to open (no chapters, or the latest chapter is finished and last).
 */
internal fun continueTarget(chapters: List<Chapter>, latestProgress: ReadProgress?): Pair<Chapter, String>? {
    val ascending = chapters.sortedBy { it.number }
    val latest = latestProgress
    val latestChapter = latest?.let { progress -> ascending.find { it.chapterId == progress.chapterId } }
    return when {
        latest == null || latestChapter == null -> ascending.firstOrNull()?.let { it to "Start reading" }
        !latest.finished -> latestChapter to
            "Continue — Ch. ${formatChapterNumber(latestChapter.number)} · p. ${latest.page + 1}"
        else -> {
            val next = ascending.getOrNull(ascending.indexOf(latestChapter) + 1)
            next?.let { it to "Continue — Ch. ${formatChapterNumber(it.number)}" }
        }
    }
}

/**
 * Session cache of loaded manga details, keyed by (sourceId, mangaId) — reused only when
 * returning from the Reader; every fresh open from a list screen invalidates the entry first, so
 * freshness is unchanged on normal navigation.
 */
class DetailsCache {
    private val entries = mutableStateMapOf<Pair<String, String>, Pair<MangaDetails, List<Chapter>>>()

    fun get(sourceId: String, mangaId: String): Pair<MangaDetails, List<Chapter>>? = entries[sourceId to mangaId]

    fun put(sourceId: String, mangaId: String, details: MangaDetails, chapters: List<Chapter>) {
        entries[sourceId to mangaId] = details to chapters
    }

    fun invalidate(sourceId: String, mangaId: String) {
        entries.remove(sourceId to mangaId)
    }
}

/**
 * Stateful loader: loads details + chapters once, tracks library membership. With
 * [autoContinue] set, fires the Continue action exactly once when the chapter list has loaded
 * and the latest-progress chapter is available in it.
 */
@Composable
fun DetailsScreen(
    sourceId: String,
    mangaId: String,
    catalog: CatalogRepository,
    library: LibraryRepository,
    downloads: DownloadManager,
    challengeSolver: ChallengeSolver,
    onOpenChapter: (Chapter, List<Chapter>) -> Unit,
    onDownloadChapter: (MangaEntry, Chapter) -> Unit = { _, _ -> },
    onDownloadAll: (MangaEntry, List<Chapter>) -> Unit = { _, _ -> },
    cache: DetailsCache = remember { DetailsCache() },
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
    val hasDownloads = mangaDownloads.isNotEmpty()
    val inLibrary = libraryItems.any { it.entry.sourceId == sourceId && it.entry.mangaId == mangaId }

    LaunchedEffect(sourceId, mangaId, reloadKey) {
        error = null
        challengeUrl = null
        try {
            val cached = cache.get(sourceId, mangaId)
            if (cached != null) {
                details = cached.first
                chapters = cached.second
            } else {
                val loadedDetails = catalog.details(sourceId, mangaId)
                val loadedChapters = catalog.chapters(sourceId, mangaId)
                cache.put(sourceId, mangaId, loadedDetails, loadedChapters)
                details = loadedDetails
                chapters = loadedChapters
            }
            // Unconditional: a manga not in the library has no library_item row, so the UPDATE
            // simply no-ops rather than needing a membership check here.
            library.setChapterCount(sourceId, mangaId, chapters.size)
            // Cheap local reads: always fresh, so reading just finished in the Reader shows up
            // immediately even when details/chapters were served from the cache above.
            finishedChapterIds = library.finishedChapterIds(sourceId, mangaId)
            latestProgress = library.latestProgress(sourceId, mangaId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChallengeRequiredException) {
            error = "This source is protected by Cloudflare"
            challengeUrl = e.url
        } catch (e: Exception) {
            error = e.message ?: "Failed to load"
        }
    }

    // Exactly-once guard: recompositions and progress refreshes after the jump must not
    // re-fire the auto-continue, or backing out of the Reader would bounce straight back in.
    val autoContinueFired = remember { mutableStateOf(false) }
    LaunchedEffect(autoContinue, chapters, latestProgress) {
        if (!autoContinue || autoContinueFired.value) return@LaunchedEffect
        val progress = latestProgress ?: return@LaunchedEffect
        if (chapters.none { it.chapterId == progress.chapterId }) return@LaunchedEffect
        val target = continueTarget(chapters, progress) ?: return@LaunchedEffect
        autoContinueFired.value = true
        onOpenChapter(target.first, chapters)
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
        currentDetails == null -> DetailsSkeleton()
        else -> DetailsScreenContent(
            details = currentDetails,
            chapters = chapters,
            inLibrary = inLibrary,
            finishedChapterIds = finishedChapterIds,
            downloadedChapterIds = downloadedChapterIds,
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
