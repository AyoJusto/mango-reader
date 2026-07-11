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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.LibraryRepository
import dev.mango.core.domain.MangaDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Pure, data-driven content — the screenshot harness renders this directly. */
@Composable
fun DetailsScreenContent(
    details: MangaDetails,
    chapters: List<Chapter>,
    inLibrary: Boolean,
    onToggleLibrary: () -> Unit,
    onOpenChapter: (Chapter) -> Unit,
) {
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
                    if (inLibrary) {
                        OutlinedButton(onClick = onToggleLibrary) { Text("In library — remove") }
                    } else {
                        Button(onClick = onToggleLibrary) { Text("Add to library") }
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
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

/** "8.0" reads as a float; chapters are "8" or "8.5". */
private fun formatChapterNumber(number: Double): String =
    if (number == number.toLong().toDouble()) number.toLong().toString() else number.toString()

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
    onOpenChapter: (Chapter) -> Unit,
) {
    var details by remember(sourceId, mangaId) { mutableStateOf<MangaDetails?>(null) }
    var chapters by remember(sourceId, mangaId) { mutableStateOf<List<Chapter>>(emptyList()) }
    var error by remember(sourceId, mangaId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val libraryItems by library.observeLibrary().collectAsState(initial = emptyList())
    val inLibrary = libraryItems.any { it.entry.sourceId == sourceId && it.entry.mangaId == mangaId }

    LaunchedEffect(sourceId, mangaId) {
        error = null
        try {
            details = catalog.details(sourceId, mangaId)
            chapters = catalog.chapters(sourceId, mangaId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Failed to load"
        }
    }

    val currentDetails = details
    val currentError = error
    when {
        currentError != null -> Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = currentError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
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
        )
    }
}
