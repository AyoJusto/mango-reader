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
import dev.mango.core.domain.LibraryRepository
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Pure, data-driven content — the screenshot harness renders this directly. */
@Composable
fun DetailsScreenContent(
    details: MangaDetails,
    chapters: List<Chapter>,
    inLibrary: Boolean,
    readChapterIds: Set<String> = emptySet(),
    onToggleLibrary: () -> Unit,
    onOpenChapter: (Chapter) -> Unit,
    onDownloadChapter: (MangaEntry, Chapter) -> Unit,
    onDownloadAll: (MangaEntry, List<Chapter>) -> Unit,
) {
    // Local UI state for the range dialog — presentation-only, never leaves this composable.
    var showRangeDialog by remember { mutableStateOf(false) }
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.padding(24.dp)) {
                Card(
                    modifier = Modifier.width(220.dp).aspectRatio(2f / 3f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    if (details.authors.isNotEmpty()) {
                        Text(
                            text = details.authors.joinToString(", "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = details.status.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    details.description?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
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
                        TextButton(onClick = { onDownloadAll(details.entry, chapters) }) {
                            Text("Download all")
                        }
                        TextButton(onClick = {
                            onDownloadAll(details.entry, chapters.filter { it.chapterId !in readChapterIds })
                        }) {
                            Text("Download unread")
                        }
                        TextButton(onClick = { showRangeDialog = true }) {
                            Text("Download range…")
                        }
                    }
                }
            }
            Text(
                text = "Chapters",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(
                    chapters.sortedByDescending { it.number },
                    key = { it.chapterId },
                ) { chapter ->
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenChapter(chapter) }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = buildString {
                                    append("Ch. ")
                                    append(formatChapterNumber(chapter.number))
                                    chapter.title?.let { append(" — "); append(it) }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = chapter.publishedAt?.let { formatDate(it) }.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { onDownloadChapter(details.entry, chapter) }) {
                                Text("↓")
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
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
                            onDownloadAll(details.entry, chapters.filter { it.number in from..to })
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
}

/** Raw Instant.toString() is machine format; readers get the date only. */
private fun formatDate(instant: kotlin.time.Instant): String =
    instant.toString().substringBefore('T')

/** Stateful loader: loads details + chapters once, tracks library membership. */
@Composable
fun DetailsScreen(
    sourceId: String,
    mangaId: String,
    catalog: CatalogRepository,
    library: LibraryRepository,
    challengeSolver: ChallengeSolver,
    onOpenChapter: (Chapter) -> Unit,
    onDownloadChapter: (MangaEntry, Chapter) -> Unit = { _, _ -> },
    onDownloadAll: (MangaEntry, List<Chapter>) -> Unit = { _, _ -> },
) {
    var details by remember(sourceId, mangaId) { mutableStateOf<MangaDetails?>(null) }
    var chapters by remember(sourceId, mangaId) { mutableStateOf<List<Chapter>>(emptyList()) }
    var readChapterIds by remember(sourceId, mangaId) { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember(sourceId, mangaId) { mutableStateOf<String?>(null) }
    var challengeUrl by remember(sourceId, mangaId) { mutableStateOf<String?>(null) }
    var solving by remember(sourceId, mangaId) { mutableStateOf(false) }
    var reloadKey by remember(sourceId, mangaId) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val libraryItems by library.observeLibrary().collectAsState(initial = emptyList())
    val inLibrary = libraryItems.any { it.entry.sourceId == sourceId && it.entry.mangaId == mangaId }

    LaunchedEffect(sourceId, mangaId, reloadKey) {
        error = null
        challengeUrl = null
        try {
            details = catalog.details(sourceId, mangaId)
            chapters = catalog.chapters(sourceId, mangaId)
            readChapterIds = library.readChapterIds(sourceId, mangaId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChallengeRequiredException) {
            error = "This source is protected by Cloudflare"
            challengeUrl = e.url
        } catch (e: Exception) {
            error = e.message ?: "Failed to load"
        }
    }

    val currentDetails = details
    val currentError = error
    when {
        currentError != null -> Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = currentError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    val url = challengeUrl
                    if (url != null) {
                        Button(
                            onClick = {
                                scope.launch {
                                    solving = true
                                    try {
                                        if (challengeSolver.solve(sourceId, url)) reloadKey++
                                    } finally {
                                        solving = false
                                    }
                                }
                            },
                            enabled = !solving,
                        ) { Text("Solve challenge") }
                        if (solving) {
                            Text(
                                text = "Opening browser… (first run downloads it, ~100MB)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        currentDetails == null -> Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        else -> DetailsScreenContent(
            details = currentDetails,
            chapters = chapters,
            inLibrary = inLibrary,
            readChapterIds = readChapterIds,
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
        )
    }
}
