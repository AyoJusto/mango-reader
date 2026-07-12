package dev.mango.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.padding(24.dp)) {
                Card(
                    modifier = Modifier.width(220.dp).aspectRatio(2f / 3f),
                    colors = CardDefaults.cardColors(containerColor = theme.bg2),
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
                Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                    Text(
                        text = details.entry.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = theme.textPrimary,
                    )
                    if (details.authors.isNotEmpty()) {
                        Text(
                            text = details.authors.joinToString(", "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = theme.textSecondary,
                        )
                    }
                    Text(
                        text = details.status.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = theme.accent,
                    )
                    details.description?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = theme.textPrimary,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (details.tags.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            details.tags.forEach { tag ->
                                AssistChip(onClick = {}, label = { Text(tag) })
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (inLibrary) {
                            OutlinedButton(onClick = onToggleLibrary) { Text("In library — remove") }
                        } else {
                            Button(onClick = onToggleLibrary) { Text("Add to library") }
                        }
                        continueTarget?.let { (chapter, label) ->
                            Button(onClick = { onOpenChapter(chapter, chapters) }) { Text(label) }
                        }
                        TextButton(onClick = {
                            onDownloadAll(details.entry, chapters.filter { it.chapterId !in downloadedChapterIds })
                        }) {
                            Text("Download all")
                        }
                        TextButton(onClick = {
                            onDownloadAll(
                                details.entry,
                                chapters.filter { it.chapterId !in finishedChapterIds && it.chapterId !in downloadedChapterIds },
                            )
                        }) {
                            Text("Download unread")
                        }
                        TextButton(onClick = { showRangeDialog = true }) {
                            Text("Download range…")
                        }
                        if (hasDownloads) {
                            TextButton(onClick = { showClearStorageDialog = true }) {
                                Text("Clear storage")
                            }
                        }
                    }
                }
            }
            Text(
                text = "Chapters",
                style = MaterialTheme.typography.titleMedium,
                color = theme.textPrimary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(
                    descendingChapters,
                    key = { it.chapterId },
                ) { chapter ->
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenChapter(chapter, chapters) }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = buildString {
                                    append("Ch. ")
                                    append(formatChapterNumber(chapter.number))
                                    chapter.title?.let { append(" — "); append(it) }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (chapter.chapterId in finishedChapterIds) {
                                    theme.textSecondary
                                } else {
                                    theme.textPrimary
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = chapter.publishedAt?.let { formatDate(it) }.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = theme.textSecondary,
                            )
                            if (chapter.chapterId in downloadedChapterIds) {
                                TextButton(onClick = {}, enabled = false) {
                                    Text("✓")
                                }
                            } else {
                                TextButton(onClick = { onDownloadChapter(details.entry, chapter) }) {
                                    Text("↓")
                                }
                            }
                        }
                        HorizontalDivider(color = theme.divider)
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

/** Raw Instant.toString() is machine format; readers get the date only. */
private fun formatDate(instant: kotlin.time.Instant): String =
    instant.toString().substringBefore('T')

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
        currentDetails == null -> Surface(modifier = Modifier.fillMaxSize(), color = theme.bg0) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
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
                    }
                }
            },
            onOpenChapter = onOpenChapter,
            onDownloadChapter = onDownloadChapter,
            onDownloadAll = onDownloadAll,
            onClearStorage = { scope.launch { downloads.clearDownloads(sourceId, mangaId) } },
        )
    }
}
