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
    var checkedAt by remember(sourceId, mangaId) { mutableStateOf<Instant?>(null) }
    var firstSeenAt by remember(sourceId, mangaId) { mutableStateOf<Map<String, Instant>>(emptyMap()) }
    var openedSnapshot by remember(sourceId, mangaId) { mutableStateOf<Instant?>(null) }
    var revalidating by remember(sourceId, mangaId) { mutableStateOf(false) }
    // Guards the openedSnapshot capture + markOpened call to run exactly once per series visit,
    // not on every reloadKey-triggered re-run of the load effect below.
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

    // True once a usable chapter list + progress pair is in state: immediately after a cache
    // hit, or after the live fetch resolves (either way) on a cold open. The auto-continue
    // fallback below must not conclude "nothing to continue into" before this point.
    var chaptersSettled by remember(sourceId, mangaId) { mutableStateOf(false) }
    LaunchedEffect(sourceId, mangaId, reloadKey) {
        error = null
        challengeUrl = null
        // Snapshot the series' previous open time BEFORE stamping a new one, then stamp — once
        // per series visit, not on every reloadKey-triggered re-run of this effect. A manga not
        // in the library has no matching item, so the snapshot stays null and markOpened no-ops.
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
        revalidating = true
        try {
            val loadedDetails = catalog.details(sourceId, mangaId)
            val loadedChapters = catalog.chapters(sourceId, mangaId)
            catalogCache.put(sourceId, mangaId, loadedDetails, loadedChapters)
            details = loadedDetails
            chapters = loadedChapters
            // The cache is the only stamper of checkedAt/firstSeenAt; re-read what put() just
            // wrote rather than computing stamps here.
            catalogCache.get(sourceId, mangaId)?.let { refreshed ->
                checkedAt = refreshed.checkedAt
                firstSeenAt = refreshed.firstSeenAt
            }
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
        revalidating = false
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
                    // The freshly inserted row starts at chapter_count 0; the loaded
                    // chapter list is at hand, so the unread badge is right immediately
                    // instead of after the next Details visit.
                    library.setChapterCount(sourceId, mangaId, chapters.size)
                }
            },
            onRemoveFromLibrary = { scope.launch { library.removeFromLibrary(sourceId, mangaId) } },
            onSetMembership = { newIds ->
                scope.launch {
                    // A checkbox toggled from outside the library must add the series first —
                    // setMembership alone would write a membership row with no library row behind
                    // it, invisible to the user.
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
