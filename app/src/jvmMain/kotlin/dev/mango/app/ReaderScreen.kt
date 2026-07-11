package dev.mango.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.ChallengeRequiredException
import dev.mango.core.domain.ChallengeSolver
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.DownloadManager
import dev.mango.core.domain.LibraryRepository
import dev.mango.core.domain.Page
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** How long the strip scrolls forward/back per Space/PageDown/PageUp keypress, as a fraction of the viewport. */
private const val PAGE_SCROLL_FRACTION = 0.9f

/** How long the controls overlay stays up after the last pointer move, in milliseconds. */
private const val CONTROLS_AUTO_HIDE_MS = 2000L

/** How close (in flattened rows) the last visible item must be to the strip's end before the next chapter auto-loads. */
private const val AUTO_LOAD_THRESHOLD = 4

/**
 * One chapter's worth of pages in the flattened strip, plus display labels — the public,
 * screenshot-harness-friendly view of a loaded chapter. [ReaderScreen]'s private [ChapterSegment]
 * (which carries the full, possibly-absent [Chapter]) is mapped down to this before reaching
 * [ReaderContent], so the pure content composable never needs a private type in its signature.
 */
data class ReaderSegment(
    val chapterId: String,
    /** "Ch. 12" — no title. Used in divider rows, which name both chapters by number only. */
    val shortLabel: String,
    /** "Ch. 12 — Title" (or just [shortLabel] when there's no title). Used as the top-bar title. */
    val label: String,
    val pages: List<Page>,
    val offline: Boolean = false,
)

/** One row in the flattened, multi-chapter LazyColumn strip. Private: an internal rendering detail. */
private sealed interface ReaderRow {
    data class PageRow(val segmentIndex: Int, val page: Page) : ReaderRow
    data class DividerRow(val toChapterId: String, val fromLabel: String, val toLabel: String) : ReaderRow
    data object EndFooterRow : ReaderRow
    data object LoadingTailRow : ReaderRow
    data class FailedTailRow(val message: String, val challengeUrl: String?) : ReaderRow
}

private fun ReaderRow.rowKey(segmentChapterId: (Int) -> String): String = when (this) {
    is ReaderRow.PageRow -> "${segmentChapterId(segmentIndex)}:${page.index}"
    is ReaderRow.DividerRow -> "div:$toChapterId"
    ReaderRow.EndFooterRow -> "tail:end"
    ReaderRow.LoadingTailRow -> "tail:loading"
    is ReaderRow.FailedTailRow -> "tail:failed"
}

/** Flattens [segments] into rows: pages, a divider between consecutive chapters, then one tail row. */
private fun buildRows(
    segments: List<ReaderSegment>,
    isLastChapter: Boolean,
    nextLoading: Boolean,
    nextError: String?,
    nextChallengeUrl: String?,
): List<ReaderRow> {
    if (segments.isEmpty()) return emptyList()
    val rows = mutableListOf<ReaderRow>()
    segments.forEachIndexed { index, segment ->
        segment.pages.forEach { page -> rows += ReaderRow.PageRow(index, page) }
        val next = segments.getOrNull(index + 1)
        if (next != null) {
            rows += ReaderRow.DividerRow(next.chapterId, segment.shortLabel, next.shortLabel)
        }
    }
    when {
        isLastChapter -> rows += ReaderRow.EndFooterRow
        nextLoading -> rows += ReaderRow.LoadingTailRow
        nextError != null -> rows += ReaderRow.FailedTailRow(nextError, nextChallengeUrl)
        else -> Unit
    }
    return rows
}

private data class ReaderPosition(val segmentIndex: Int, val pageIndex: Int)

/**
 * Maps a flattened row index to the segment currently "current" there. Divider and tail rows
 * attribute to the PRECEDING segment (its pages already finished, its chapter not yet begun) —
 * never mark the next chapter read while only its divider has scrolled into view.
 */
private fun currentPosition(rows: List<ReaderRow>, flattenedIndex: Int): ReaderPosition? {
    if (rows.isEmpty()) return null
    val clamped = flattenedIndex.coerceIn(0, rows.lastIndex)
    for (i in clamped downTo 0) {
        val row = rows[i]
        if (row is ReaderRow.PageRow) return ReaderPosition(row.segmentIndex, row.page.index)
    }
    return null
}

/** The chapter immediately after [currentChapterId] in [chapters], or null at the last chapter (or if not found). */
private fun nextChapter(chapters: List<Chapter>, currentChapterId: String): Chapter? {
    val index = chapters.indexOfFirst { it.chapterId == currentChapterId }
    if (index == -1 || index == chapters.lastIndex) return null
    return chapters[index + 1]
}

/** The chapter immediately before [currentChapterId] in [chapters], or null at the first chapter (or if not found). */
private fun previousChapter(chapters: List<Chapter>, currentChapterId: String): Chapter? {
    val index = chapters.indexOfFirst { it.chapterId == currentChapterId }
    if (index <= 0) return null
    return chapters[index - 1]
}

/**
 * Pure, data-driven long-strip content — the screenshot harness renders this directly.
 * Full-bleed pages of every loaded chapter in one [LazyColumn] on [ReaderBlack], centered at a
 * max width so ultrawide monitors don't stretch pages absurdly, with a fading controls overlay
 * on top. [chapters] only backs the Prev/Next enabled-state (whether a neighbor exists); loading
 * a neighbor is [ReaderScreen]'s job.
 */
@Composable
fun ReaderContent(
    segments: List<ReaderSegment>,
    chapters: List<Chapter> = emptyList(),
    listState: LazyListState,
    controlsVisible: Boolean,
    isLastChapter: Boolean = true,
    nextLoading: Boolean = false,
    nextError: String? = null,
    nextChallengeUrl: String? = null,
    onBack: () -> Unit,
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
    onRetryNext: () -> Unit = {},
    onSolveNextChallenge: () -> Unit = {},
    nextChallengeSolving: Boolean = false,
    pageContent: @Composable (Page, Boolean) -> Unit,
) {
    val rows = remember(segments, isLastChapter, nextLoading, nextError, nextChallengeUrl) {
        buildRows(segments, isLastChapter, nextLoading, nextError, nextChallengeUrl)
    }
    val position = currentPosition(rows, listState.firstVisibleItemIndex)
    val currentSegment = position?.let { segments.getOrNull(it.segmentIndex) } ?: segments.firstOrNull()
    val currentChapterId = currentSegment?.chapterId
    val prevEnabled = currentChapterId != null && previousChapter(chapters, currentChapterId) != null
    val nextEnabled = currentChapterId != null && nextChapter(chapters, currentChapterId) != null
    val counterText = buildString {
        if (currentSegment != null && position != null) {
            append(position.pageIndex + 1)
            append(" / ")
            append(currentSegment.pages.size)
            if (currentSegment.offline) append(" · offline")
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = ReaderBlack) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxHeight()
                    .widthIn(max = 1000.dp)
                    .fillMaxWidth(),
            ) {
                items(rows, key = { row -> row.rowKey { index -> segments[index].chapterId } }) { row ->
                    when (row) {
                        is ReaderRow.PageRow -> pageContent(row.page, segments[row.segmentIndex].offline)
                        is ReaderRow.DividerRow -> ChapterDividerRow(row.fromLabel, row.toLabel)
                        ReaderRow.EndFooterRow -> EndOfStripRow()
                        ReaderRow.LoadingTailRow -> LoadingTailRow()
                        is ReaderRow.FailedTailRow -> FailedTailRow(
                            message = row.message,
                            challengeUrl = row.challengeUrl,
                            onRetry = onRetryNext,
                            onSolveChallenge = onSolveNextChallenge,
                            solving = nextChallengeSolving,
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            ) {
                Surface(color = MaterialTheme.colorScheme.background.copy(alpha = 0.85f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Text("←", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            text = currentSegment?.label.orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(modifier = Modifier.fillMaxWidth().weight(1f))
                        TextButton(onClick = onPrev, enabled = prevEnabled) { Text("‹ Prev") }
                        TextButton(onClick = onNext, enabled = nextEnabled) { Text("Next ›") }
                        Text(
                            text = counterText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterDividerRow(fromLabel: String, toLabel: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "$fromLabel — end · $toLabel",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EndOfStripRow() {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "No more chapters",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadingTailRow() {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun FailedTailRow(
    message: String,
    challengeUrl: String?,
    onRetry: () -> Unit,
    onSolveChallenge: () -> Unit,
    solving: Boolean = false,
) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onRetry, enabled = !solving) { Text("Retry") }
            if (challengeUrl != null) {
                Button(onClick = onSolveChallenge, enabled = !solving) { Text("Solve challenge") }
            }
        }
    }
}

/** One loaded chapter in the strip. [chapter] is null only when the opened chapterId isn't in the caller's [Chapter] list. */
private data class ChapterSegment(
    val chapter: Chapter?,
    val chapterId: String,
    val pages: List<Page>,
    val offline: Boolean,
)

private fun ChapterSegment.toReaderSegment(): ReaderSegment {
    val short = chapter?.let { "Ch. " + formatChapterNumber(it.number) } ?: "Ch. ?"
    val full = chapter?.let { c ->
        buildString {
            append(short)
            c.title?.let { append(" — "); append(it) }
        }
    } ?: short
    return ReaderSegment(chapterId = chapterId, shortLabel = short, label = full, pages = pages, offline = offline)
}

/** Idle | Loading | Failed state machine for the next-chapter auto-append / N-key load. */
private sealed interface NextLoadState {
    data object Idle : NextLoadState
    data object Loading : NextLoadState
    data class Failed(val message: String, val challengeUrl: String?) : NextLoadState
}

/**
 * A fully downloaded chapter reads from disk, no network at all; anything short of that (not
 * downloaded, still in progress, failed) falls back to the live catalog. Throws on failure
 * (including [ChallengeRequiredException]) — callers catch.
 */
private suspend fun loadChapterPages(
    sourceId: String,
    mangaId: String,
    chapterId: String,
    catalog: CatalogRepository,
    downloads: DownloadManager,
): Pair<List<Page>, Boolean> {
    val local = downloads.localPages(sourceId, mangaId, chapterId)
    return if (local != null) {
        local.mapIndexed { index, path -> Page(index = index, url = path) } to true
    } else {
        catalog.pages(sourceId, mangaId, chapterId) to false
    }
}

/**
 * Stateful loader: loads the anchor chapter's pages, restores saved progress, appends the next
 * chapter as the strip nears its end (infinite scroll), and owns all reader input (keyboard
 * paging/chapter-nav, fullscreen toggle, auto-hiding controls). Persists progress with a short
 * debounce so scroll-driven recomposition doesn't hammer [library].
 */
@OptIn(FlowPreview::class, ExperimentalComposeUiApi::class)
@Composable
fun ReaderScreen(
    sourceId: String,
    mangaId: String,
    chapterId: String,
    chapters: List<Chapter>,
    catalog: CatalogRepository,
    downloads: DownloadManager,
    library: LibraryRepository,
    challengeSolver: ChallengeSolver,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    progressDebounceMillis: Long = 500,
    pageContent: (@Composable (Page) -> Unit)? = null,
    // M6a: while the palette overlay is up it owns the keyboard; when it closes, the reader
    // must re-request focus or its shortcuts stay dead (focus went to the palette's field)
    paletteVisible: Boolean = false,
) {
    // The chapter currently anchoring the strip's first-loaded segment. Opening the reader sets
    // it to chapterId; pressing P re-anchors it to the previous chapter (a fresh "open", not an
    // upward prepend) — every per-chapter remember/effect below keys off this, not chapterId.
    var anchorChapterId by remember(sourceId, mangaId, chapterId) { mutableStateOf(chapterId) }
    var savedPage by remember(sourceId, mangaId, anchorChapterId) { mutableStateOf<Int?>(null) }
    var error by remember(sourceId, mangaId, anchorChapterId) { mutableStateOf<String?>(null) }
    var challengeUrl by remember(sourceId, mangaId, anchorChapterId) { mutableStateOf<String?>(null) }
    var solving by remember(sourceId, mangaId, anchorChapterId) { mutableStateOf(false) }
    var reloadKey by remember(sourceId, mangaId, anchorChapterId) { mutableStateOf(0) }
    var segments by remember(sourceId, mangaId, anchorChapterId) { mutableStateOf<List<ChapterSegment>>(emptyList()) }
    var nextLoad by remember(sourceId, mangaId, anchorChapterId) { mutableStateOf<NextLoadState>(NextLoadState.Idle) }
    var solvingNext by remember(sourceId, mangaId, anchorChapterId) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var visibilityTick by remember { mutableStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }

    // Local helpers, all reading the vars above fresh at call time — reused by effects and key
    // handlers so the flatten/attribute math lives in exactly one place.
    fun displaySegments(): List<ReaderSegment> = segments.map { it.toReaderSegment() }
    fun isLastLoadedChapter(): Boolean {
        val last = segments.lastOrNull() ?: return true
        return nextChapter(chapters, last.chapterId) == null
    }
    fun currentRows(): List<ReaderRow> = buildRows(
        displaySegments(),
        isLastLoadedChapter(),
        nextLoad is NextLoadState.Loading,
        (nextLoad as? NextLoadState.Failed)?.message,
        (nextLoad as? NextLoadState.Failed)?.challengeUrl,
    )
    fun currentSegmentAndPage(): ReaderPosition? = currentPosition(currentRows(), listState.firstVisibleItemIndex)

    suspend fun appendNextSegment(next: Chapter) {
        nextLoad = NextLoadState.Loading
        try {
            val (loadedPages, isOffline) = loadChapterPages(sourceId, mangaId, next.chapterId, catalog, downloads)
            segments = segments + ChapterSegment(next, next.chapterId, loadedPages, isOffline)
            nextLoad = NextLoadState.Idle
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChallengeRequiredException) {
            nextLoad = NextLoadState.Failed("This source is protected by Cloudflare", e.url)
        } catch (e: Exception) {
            nextLoad = NextLoadState.Failed(e.message ?: "Failed to load", null)
        }
    }

    // Shared by the N/P key handlers and the overlay's Next/Prev buttons — same action, two
    // entry points; extracted so the two can't drift.
    fun goToNextChapter() {
        scope.launch {
            val position = currentSegmentAndPage() ?: return@launch
            val current = segments.getOrNull(position.segmentIndex) ?: return@launch
            val next = nextChapter(chapters, current.chapterId) ?: return@launch
            if (segments.none { it.chapterId == next.chapterId }) {
                if (nextLoad != NextLoadState.Idle) return@launch
                appendNextSegment(next)
            }
            val targetIndex = segments.indexOfFirst { it.chapterId == next.chapterId }
            if (targetIndex == -1) return@launch
            val flatIndex = currentRows().indexOfFirst {
                it is ReaderRow.PageRow && it.segmentIndex == targetIndex
            }
            if (flatIndex >= 0) listState.animateScrollToItem(flatIndex)
        }
    }

    fun reanchorToPreviousChapter() {
        val position = currentSegmentAndPage()
        val current = position?.let { segments.getOrNull(it.segmentIndex) }
        val prev = current?.let { previousChapter(chapters, it.chapterId) }
        if (prev != null) anchorChapterId = prev.chapterId
    }

    LaunchedEffect(sourceId, mangaId, anchorChapterId, reloadKey) {
        error = null
        challengeUrl = null
        try {
            savedPage = library.progress(sourceId, mangaId, anchorChapterId)?.page
            val (loadedPages, isOffline) = loadChapterPages(sourceId, mangaId, anchorChapterId, catalog, downloads)
            segments = listOf(
                ChapterSegment(
                    chapter = chapters.find { it.chapterId == anchorChapterId },
                    chapterId = anchorChapterId,
                    pages = loadedPages,
                    offline = isOffline,
                ),
            )
            nextLoad = NextLoadState.Idle
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChallengeRequiredException) {
            error = "This source is protected by Cloudflare"
            challengeUrl = e.url
        } catch (e: Exception) {
            error = e.message ?: "Failed to load"
        }
    }

    // Restore the saved scroll position before we start observing it, so the initial value fed
    // into the debounced writer below is already the resumed page — never a spurious 0 that
    // would race the restore and stomp the progress we just loaded. Keyed on the first segment's
    // chapterId: it's set exactly once per anchor (appending later chapters doesn't change it),
    // so this effect's lifetime spans the whole time that anchor is being read.
    val firstSegmentChapterId = segments.firstOrNull()?.chapterId
    LaunchedEffect(firstSegmentChapterId) {
        if (firstSegmentChapterId == null) return@LaunchedEffect
        val firstSegment = segments.first()
        val saved = savedPage
        listState.scrollToItem(if (saved != null && saved in firstSegment.pages.indices) saved else 0)

        snapshotFlow {
            val position = currentPosition(currentRows(), listState.firstVisibleItemIndex)
            position?.let { p -> segments.getOrNull(p.segmentIndex)?.let { seg -> seg.chapterId to p.pageIndex } }
        }
            .distinctUntilChanged()
            .debounce(progressDebounceMillis)
            .collect { current ->
                if (current != null) library.setProgress(sourceId, mangaId, current.first, page = current.second)
            }
    }

    // Auto-append: once the strip's tail is close, and nothing is already loading, fetch the
    // next chapter and append it. The nextLoad-is-Idle check is the single in-flight-load gate.
    LaunchedEffect(sourceId, mangaId, anchorChapterId) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisible ->
                if (lastVisible == null) return@collect
                if (nextLoad != NextLoadState.Idle) return@collect
                val rows = currentRows()
                if (rows.isEmpty()) return@collect
                if (rows.lastIndex - lastVisible > AUTO_LOAD_THRESHOLD) return@collect
                val lastSegment = segments.lastOrNull() ?: return@collect
                val next = nextChapter(chapters, lastSegment.chapterId) ?: return@collect
                appendNextSegment(next)
            }
    }

    LaunchedEffect(visibilityTick) {
        controlsVisible = true
        delay(CONTROLS_AUTO_HIDE_MS)
        controlsVisible = false
    }

    val currentError = error
    when {
        currentError != null -> Surface(modifier = Modifier.fillMaxSize(), color = ReaderBlack) {
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
        segments.isEmpty() -> Surface(modifier = Modifier.fillMaxSize(), color = ReaderBlack) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        else -> {
            // Requesting focus here — not in a top-level LaunchedEffect(Unit) — matters: this
            // branch is the first point in composition where Modifier.focusRequester below is
            // actually attached to a node. Requesting focus before that (e.g. while the loading
            // spinner above is still showing) targets nothing and silently no-ops. Keyed on
            // paletteVisible (M6a): the initial composition still requests (the flag starts
            // false), and closing the palette re-requests — otherwise the palette's text field
            // keeps focus and the reader's keyboard is dead after the overlay closes.
            LaunchedEffect(paletteVisible) { if (!paletteVisible) focusRequester.requestFocus() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPointerEvent(PointerEventType.Move) { visibilityTick++ }
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (keyEvent.key) {
                            Key.Spacebar, Key.PageDown, Key.DirectionDown -> {
                                scope.launch {
                                    listState.animateScrollBy(
                                        listState.layoutInfo.viewportSize.height * PAGE_SCROLL_FRACTION,
                                    )
                                }
                                true
                            }
                            Key.PageUp, Key.DirectionUp -> {
                                scope.launch {
                                    listState.animateScrollBy(
                                        -listState.layoutInfo.viewportSize.height * PAGE_SCROLL_FRACTION,
                                    )
                                }
                                true
                            }
                            Key.N -> {
                                goToNextChapter()
                                true
                            }
                            Key.P -> {
                                reanchorToPreviousChapter()
                                true
                            }
                            Key.F -> {
                                onToggleFullscreen()
                                true
                            }
                            Key.Escape -> {
                                onBack()
                                true
                            }
                            else -> false
                        }
                    },
            ) {
                ReaderContent(
                    segments = displaySegments(),
                    chapters = chapters,
                    listState = listState,
                    controlsVisible = controlsVisible,
                    isLastChapter = isLastLoadedChapter(),
                    nextLoading = nextLoad is NextLoadState.Loading,
                    nextError = (nextLoad as? NextLoadState.Failed)?.message,
                    nextChallengeUrl = (nextLoad as? NextLoadState.Failed)?.challengeUrl,
                    nextChallengeSolving = solvingNext,
                    onBack = onBack,
                    onPrev = { reanchorToPreviousChapter() },
                    onNext = { goToNextChapter() },
                    onRetryNext = {
                        scope.launch {
                            val lastSegment = segments.lastOrNull() ?: return@launch
                            val next = nextChapter(chapters, lastSegment.chapterId) ?: return@launch
                            appendNextSegment(next)
                        }
                    },
                    onSolveNextChallenge = {
                        val url = (nextLoad as? NextLoadState.Failed)?.challengeUrl
                        if (url != null) {
                            scope.launch {
                                solvingNext = true
                                try {
                                    if (challengeSolver.solve(sourceId, url)) {
                                        val lastSegment = segments.lastOrNull()
                                        val next = lastSegment?.let { nextChapter(chapters, it.chapterId) }
                                        if (next != null) appendNextSegment(next)
                                    }
                                } finally {
                                    solvingNext = false
                                }
                            }
                        }
                    },
                    // local-file loading is gated on OUR offline state, never on the shape of
                    // page.url: an extension-returned "URL" must not be able to name a disk path
                    pageContent = { page, offline ->
                        if (pageContent != null) pageContent(page) else DefaultReaderPage(page, local = offline)
                    },
                )
            }
        }
    }
}

/**
 * Default page renderer: a Coil [SubcomposeAsyncImage] carrying the page's host-required
 * headers (auth, referer) through [httpHeaders]. Plain `AsyncImage`'s placeholder slot only
 * takes a static Painter, so this uses SubcomposeAsyncImage to draw the composable loading
 * box the reader spec calls for (a tinted panel with a spinner) while a page is in flight.
 *
 * [Page] is reused for offline reading too ([ReaderScreen] fills `url` with a local absolute
 * path when the chapter is fully downloaded). Which branch runs is decided by [local] — the
 * reader's own offline state — NEVER by inspecting the url string: page URLs come from
 * untrusted extension output and must not be able to point the app at a disk path.
 */
@Composable
private fun DefaultReaderPage(page: Page, local: Boolean) {
    val context = LocalPlatformContext.current
    val request = remember(page, local) {
        val builder = ImageRequest.Builder(context)
        if (local) {
            builder.data(File(page.url))
        } else {
            val headers = NetworkHeaders.Builder().apply {
                page.headers.forEach { (name, value) -> set(name, value) }
            }.build()
            builder.data(page.url).httpHeaders(headers)
        }
        builder
            // per-request: loader-level cap doesn't reach decode in coil 3.5.0 (ImageLoading.kt)
            .maxBitmapSize(WebtoonMaxBitmapSize)
            .build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        // pages render 800px sources into wider viewports; low (default) filtering pixelates
        filterQuality = FilterQuality.High,
        modifier = Modifier.fillMaxWidth(),
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(600.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        },
    )
}
