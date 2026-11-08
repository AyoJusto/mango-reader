package dev.mango.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
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
import dev.mango.core.domain.CatalogCache
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
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Paints the cached entry first, then always revalidates live. */
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
    var checkedAt by remember(sourceId, mangaId) { mutableStateOf<Instant?>(null) }
    var firstSeenAt by remember(sourceId, mangaId) { mutableStateOf<Map<String, Instant>>(emptyMap()) }
    var openedSnapshot by remember(sourceId, mangaId) { mutableStateOf<Instant?>(null) }
    var revalidating by remember(sourceId, mangaId) { mutableStateOf(false) }
    var openedCaptured by remember(sourceId, mangaId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val libraryItems by library.observeLibrary().collectAsState(initial = emptyList())
    val collections by library.observeCollections().collectAsState(initial = emptyList())
    val mangaDownloads by remember(sourceId, mangaId) {
        downloads.observeDownloads().map { rows -> rows.filter { it.sourceId == sourceId && it.mangaId == mangaId } }
    }.collectAsState(initial = emptyList())
    val downloadedChapterIds = mangaDownloads.filter { it.status == DownloadStatus.DONE }.map { it.chapterId }.toSet()
    val downloadsByChapterId = mangaDownloads.associateBy { it.chapterId }
    val hasDownloads = mangaDownloads.isNotEmpty()
    val currentLibraryItem = libraryItems.find { it.entry.sourceId == sourceId && it.entry.mangaId == mangaId }
    val inLibrary = currentLibraryItem != null

    var chaptersSettled by remember(sourceId, mangaId) { mutableStateOf(false) }
    LaunchedEffect(sourceId, mangaId, reloadKey) {
        error = null
        challengeUrl = null
        // Snapshot the previous open time before stamping a new one.
        if (!openedCaptured) {
            openedSnapshot = library.observeLibrary().first()
                .find { it.entry.sourceId == sourceId && it.entry.mangaId == mangaId }
                ?.lastOpenedAt
            library.markOpened(sourceId, mangaId)
            openedCaptured = true
        }
        val cached = catalogCache.get(sourceId, mangaId)
        if (cached != null) {
            details = cached.details
            chapters = cached.chapters
            checkedAt = cached.checkedAt
            firstSeenAt = cached.firstSeenAt
            library.setChapterCount(sourceId, mangaId, chapters.size)
            finishedChapterIds = library.finishedChapterIds(sourceId, mangaId)
            latestProgress = library.latestProgress(sourceId, mangaId)
            chaptersSettled = true
        }
        // A cached copy on screen makes live failure silent; the Reader's Solve button refreshes clearance.
        revalidating = true
        try {
            val loadedDetails = catalog.details(sourceId, mangaId)
            val loadedChapters = catalog.chapters(sourceId, mangaId)
            catalogCache.put(sourceId, mangaId, loadedDetails, loadedChapters)
            details = loadedDetails
            chapters = loadedChapters
            catalogCache.get(sourceId, mangaId)?.let { refreshed ->
                checkedAt = refreshed.checkedAt
                firstSeenAt = refreshed.firstSeenAt
            }
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
        revalidating = false
        chaptersSettled = true
    }

    // The veil hides Details during a pending auto-continue; it drops only after chaptersSettled.
    val passThrough = remember(sourceId, mangaId) { mutableStateOf(autoContinue) }
    // Exactly-once so backing out of the Reader doesn't bounce straight back in.
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

    val snapshot = openedSnapshot
    val newChapterIds = if (snapshot == null) {
        emptySet()
    } else {
        chapters.filter { firstSeenAt[it.chapterId]?.let { seenAt -> seenAt > snapshot } == true }
            .map { it.chapterId }
            .toSet()
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
            newChapterIds = newChapterIds,
            checkedAt = checkedAt,
            revalidating = revalidating,
            onRefresh = { reloadKey++ },
            collections = collections,
            memberCollectionIds = currentLibraryItem?.collectionIds ?: emptySet(),
            onAddToLibrary = {
                scope.launch {
                    library.addToLibrary(currentDetails.entry)
                    library.setChapterCount(sourceId, mangaId, chapters.size)
                }
            },
            onRemoveFromLibrary = { scope.launch { library.removeFromLibrary(sourceId, mangaId) } },
            onSetMembership = { newIds ->
                scope.launch {
                    // Add the series first: setMembership alone leaves a membership row with no library row.
                    if (!inLibrary) {
                        library.addToLibrary(currentDetails.entry)
                        library.setChapterCount(sourceId, mangaId, chapters.size)
                    }
                    library.setMembership(sourceId, mangaId, newIds)
                }
            },
            onCreateCollection = { name -> library.createCollection(name) },
            onOpenChapter = onOpenChapter,
            onDownloadChapter = onDownloadChapter,
            onDownloadAll = onDownloadAll,
            onClearStorage = { scope.launch { downloads.clearDownloads(sourceId, mangaId) } },
            onMarkAllFinished = {
                scope.launch {
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
                    finishedChapterIds = library.finishedChapterIds(sourceId, mangaId)
                    latestProgress = library.latestProgress(sourceId, mangaId)
                }
            },
        )
    }
}
