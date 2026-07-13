package dev.mango.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.SingletonImageLoader
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
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** How long the strip scrolls forward/back per Space/PageDown/PageUp keypress, as a fraction of the viewport. */
private const val PAGE_SCROLL_FRACTION = 0.9f

/** How long the controls overlay stays up after the last reveal — mirrors [MangoMotion.READER_IDLE_MS]. */
private val CONTROLS_AUTO_HIDE_MS = MangoMotion.READER_IDLE_MS.toLong()

/** How close (in flattened rows) the last visible item must be to the strip's end before the next chapter auto-loads. */
private const val AUTO_LOAD_THRESHOLD = 4

/** How many upcoming pages the reader prefetches ahead of the last visible row. */
private const val PREFETCH_PAGE_COUNT = 5

/**
 * Pointer moves at least this far (px) from the previous sample count as a genuine reveal.
 * Compose desktop emits synthetic hover-move events at (near) the same position during a
 * stationary-cursor wheel scroll — those land under this threshold and must not reveal.
 */
private const val OVERLAY_MOVE_THRESHOLD_PX = 2f

/**
 * The move-delta gate for the reader's controls overlay: decides whether a pointer sample counts
 * as a genuine reveal (moved at least [OVERLAY_MOVE_THRESHOLD_PX] from the previous sample). No
 * Compose or coroutine dependency, so unit tests exercise it directly. [ReaderScreen] wires the
 * returned decision into its pointer handler; the idle-hide and palette-pin scheduling live in a
 * Compose effect there (delay-based, proven to cooperate with the Compose test clock, unlike a
 * polling loop — see the auto-scroll drive loop's own comments).
 */
internal class ReaderOverlayState {
    private var lastPointer: Offset? = null

    /**
     * A pointer sample; returns true if it counts as a genuine reveal. The first sample only
     * establishes a baseline position and never reveals by itself.
     */
    fun onPointerMove(position: Offset): Boolean {
        val last = lastPointer
        lastPointer = position
        return last != null && (position - last).getDistance() >= OVERLAY_MOVE_THRESHOLD_PX
    }
}

/** A fully transparent 1x1 AWT cursor: the reader hides the mouse cursor together with the controls overlay. */
private val BLANK_CURSOR_ICON: PointerIcon by lazy {
    val transparentPixel = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(transparentPixel, Point(0, 0), "mango-reader-hidden-cursor"))
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
    // Injectable so tests can make the auto-hide deterministic (same precedent as
    // progressDebounceMillis above). Production doesn't pass it.
    controlsAutoHideMillis: Long = CONTROLS_AUTO_HIDE_MS,
    // dp/sec driving the A-key auto-scroll loop; persisted via Settings, plumbed down as a
    // plain composable param (not part of Screen.Reader nav state).
    autoScrollSpeedDpPerSec: Float = 120f,
    // The reading strip's width; a user-facing width slider is not wired yet.
    stripWidthDp: Float = DEFAULT_STRIP_WIDTH_DP,
    pageContent: (@Composable (Page) -> Unit)? = null,
    // While the palette overlay is up it owns the keyboard; when it closes the reader must
    // re-request focus or its shortcuts stay dead (focus went to the palette's field)
    paletteVisible: Boolean = false,
    // Gates only the cursor-blanking half of the controls-overlay behavior; the overlay itself
    // still hides on idle regardless of this flag.
    hideCursorInReader: Boolean = true,
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
    // Keyed on the anchor: P re-anchors to the previous chapter, and a fresh scroll state per
    // anchor means the strip never renders one frame of the OLD chapter's scroll offset before
    // the restore effect repositions it.
    val listState = remember(sourceId, mangaId, anchorChapterId) { LazyListState() }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    // Bumped on every genuine reveal (a pointer move past ReaderOverlayState's delta gate, or a
    // click while hidden); the effect below (keyed on this) is what actually schedules the hide.
    var revealVersion by remember { mutableStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }
    // Deliberately unkeyed on any per-chapter identity: auto-appending the next chapter (the
    // infinite-scroll effect above) must not stop the scroll mid-flight.
    var autoScrolling by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    // Pure move-delta gate backing the pointer handlers below; also unkeyed on chapter identity,
    // same reasoning as autoScrolling above — it tracks the pointer across the reader's whole
    // lifetime, not per anchor.
    val overlayState = remember { ReaderOverlayState() }
    // Captured here (composition), not inside the prefetch effect below (a suspend block, no
    // CompositionLocal access) — the default renderer's DefaultReaderPage reads the same local.
    val platformContext = LocalPlatformContext.current
    // Learned width/height ratio per page url, hoisted above the LazyColumn so it survives item
    // recycling: a page scrolled back into view must reserve its REAL height immediately. If it
    // collapsed to the fallback ratio and re-grew on load, content above the viewport would
    // shrink-then-stretch under an upward scroll and the strip's start could never be reached.
    val pageAspectRatios = remember { mutableStateMapOf<String, Float>() }

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
    // entry points; extracted so the two can't drift. Both stop auto-scroll HERE, not at the
    // call sites: a re-anchor mints a fresh listState, and a drive loop left running would
    // keep scrolling the old detached one.
    fun goToNextChapter() {
        autoScrolling = false
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
        autoScrolling = false
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
            .debounce(progressDebounceMillis.milliseconds)
            .collect { current ->
                if (current != null) {
                    library.setProgress(
                        sourceId,
                        mangaId,
                        current.first,
                        page = current.second,
                        chapterNumber = chapters.find { it.chapterId == current.first }?.number ?: 0.0,
                    )
                }
            }
    }

    // Chapter completion writes deliberately do NOT ride the debounced writer above: finished's
    // window (chapter still current AND its last page revealed) is transient during a continuous
    // binge-scroll into the next chapter, and debounce would coalesce the emission away — the
    // fast-read flow would never mark anything finished. Completion is once-per-chapter rare,
    // so it writes immediately; the SQL MAX keeps the flag sticky and
    // makes any duplicate write harmless. A chapter counts as finished once its LAST PageRow has
    // scrolled into (or past) view — dividers/footers after it count too, so a short final page
    // doesn't have to reach the top of the viewport to register.
    LaunchedEffect(firstSegmentChapterId) {
        if (firstSegmentChapterId == null) return@LaunchedEffect
        val written = mutableSetOf<String>()
        snapshotFlow {
            val rows = currentRows()
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?: return@snapshotFlow emptySet<String>()
            segments.indices.filter { segmentIndex ->
                val lastPageRowIndex = rows.indexOfLast { row ->
                    row is ReaderRow.PageRow && row.segmentIndex == segmentIndex
                }
                lastPageRowIndex != -1 && lastVisibleIndex >= lastPageRowIndex
            }.mapNotNull { segments.getOrNull(it)?.chapterId }.toSet()
        }
            .distinctUntilChanged()
            .collect { completed ->
                completed.filter(written::add).forEach { chapterId ->
                    val lastPage = (segments.find { it.chapterId == chapterId }?.pages?.size ?: 1) - 1
                    library.setProgress(
                        sourceId,
                        mangaId,
                        chapterId,
                        page = lastPage,
                        finished = true,
                        chapterNumber = chapters.find { it.chapterId == chapterId }?.number ?: 0.0,
                    )
                }
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

    // Prefetch: warms Coil's cache for the next few pages so they're already loading by the time
    // the strip scrolls to them. Only for the default renderer — a custom pageContent (tests,
    // the screenshot harness) must never trigger network enqueues.
    if (pageContent == null) {
        LaunchedEffect(sourceId, mangaId, anchorChapterId) {
            val imageLoader = SingletonImageLoader.get(platformContext)
            val enqueuedPageUrls = mutableSetOf<String>()
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                .collect { lastVisible ->
                    if (lastVisible == null) return@collect
                    pagesToPrefetch(currentRows(), displaySegments(), lastVisible, PREFETCH_PAGE_COUNT)
                        .forEach { page ->
                            if (enqueuedPageUrls.add(page.url)) {
                                imageLoader.enqueue(networkPageRequest(platformContext, page))
                            }
                        }
                }
        }
    }

    // A one-shot delay per reveal, cancelled and restarted on the next one — proven to cooperate
    // with the Compose test clock (rule.mainClock.advanceTimeBy), unlike a polling loop (which
    // would never go idle; see the auto-scroll loop's own comments below). Pinned while the
    // palette is up: it owns the keyboard and the screen, so no hide is ever scheduled until
    // it closes.
    LaunchedEffect(revealVersion, paletteVisible) {
        controlsVisible = true
        if (paletteVisible) return@LaunchedEffect
        delay(controlsAutoHideMillis.milliseconds)
        controlsVisible = false
    }

    // Auto-scroll drive loop: dt from the frame clock, suspend scrollBy between frames. While
    // the palette overlay is up the reader has no keyboard (A can't stop the strip), so the
    // loop pauses and auto-resumes on close — autoScrolling itself stays true.
    LaunchedEffect(autoScrolling, autoScrollSpeedDpPerSec, paletteVisible) {
        if (!autoScrolling || paletteVisible) return@LaunchedEffect
        val speedPx = with(density) { autoScrollSpeedDpPerSec.dp.toPx() }
        var lastFrameNanos = -1L
        while (autoScrolling) {
            val frameNanos = withFrameNanos { it }
            if (lastFrameNanos >= 0) {
                val delta = speedPx * (frameNanos - lastFrameNanos) / 1_000_000_000f
                if (delta > 0f && listState.scrollBy(delta) == 0f) {
                    // Hard end of the LOADED strip. If the next chapter is merely still
                    // loading, keep the loop alive so reading resumes when it appends; stop
                    // for real at the last chapter or on a failed append (Retry is manual).
                    if (isLastLoadedChapter() || nextLoad is NextLoadState.Failed) {
                        autoScrolling = false
                    }
                }
            }
            lastFrameNanos = frameNanos
        }
    }

    val currentError = error
    when {
        currentError != null -> Surface(modifier = Modifier.fillMaxSize(), color = LocalMangoTheme.current.bg0) {
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
        segments.isEmpty() -> Surface(modifier = Modifier.fillMaxSize(), color = LocalMangoTheme.current.bg0) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        else -> {
            // Requesting focus here — not in a top-level LaunchedEffect(Unit) — matters: this
            // branch is the first point in composition where Modifier.focusRequester below is
            // actually attached to a node. Requesting focus before that (e.g. while the loading
            // spinner above is still showing) targets nothing and silently no-ops. Keyed on
            // paletteVisible: the initial composition still requests (the flag starts false),
            // and closing the palette re-requests — otherwise the palette's text field keeps
            // focus and the reader's keyboard is dead after the overlay closes.
            LaunchedEffect(paletteVisible) { if (!paletteVisible) focusRequester.requestFocus() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
                    // The cursor hides together with the overlay; restored the instant
                    // controlsVisible flips back (never leaks past this Box's lifetime). Only the
                    // blanking is conditional on hideCursorInReader — the overlay itself always hides.
                    .pointerHoverIcon(
                        if (controlsVisible || !hideCursorInReader) PointerIcon.Default else BLANK_CURSOR_ICON,
                        overrideDescendants = true,
                    )
                    .onPointerEvent(PointerEventType.Move) { event ->
                        val position = event.changes.firstOrNull()?.position
                        // Keyboard paging/N/P never reach this handler — only a genuine pointer
                        // move (gated by ReaderOverlayState) or a click reveals.
                        if (position != null && overlayState.onPointerMove(position)) {
                            revealVersion++
                        }
                    }
                    .onPointerEvent(PointerEventType.Press) { event ->
                        // Presses on the overlay's own controls arrive here already consumed by
                        // their clickable (the Main pass runs children first) and must not also
                        // toggle. An unconsumed click toggles: reveals when hidden, dismisses
                        // when up — so the overlay can be waved away without waiting out the
                        // idle timer.
                        if (event.changes.none { it.isConsumed }) {
                            if (controlsVisible) controlsVisible = false else revealVersion++
                        }
                    }
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (keyEvent.key) {
                            Key.Spacebar, Key.PageDown, Key.DirectionDown -> {
                                autoScrolling = false
                                scope.launch {
                                    listState.animateScrollBy(
                                        listState.layoutInfo.viewportSize.height * PAGE_SCROLL_FRACTION,
                                    )
                                }
                                true
                            }
                            Key.PageUp, Key.DirectionUp -> {
                                autoScrolling = false
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
                            Key.A -> {
                                autoScrolling = !autoScrolling
                                true
                            }
                            Key.F -> {
                                onToggleFullscreen()
                                true
                            }
                            Key.Escape -> {
                                autoScrolling = false
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
                    autoScrolling = autoScrolling,
                    stripWidthDp = stripWidthDp,
                    onBack = onBack,
                    onPrev = { reanchorToPreviousChapter() },
                    onNext = { goToNextChapter() },
                    onToggleAutoScroll = { autoScrolling = !autoScrolling },
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
                        if (pageContent != null) {
                            pageContent(page)
                        } else {
                            DefaultReaderPage(page, local = offline, aspectRatios = pageAspectRatios)
                        }
                    },
                )
            }
        }
    }
}

/**
 * Builds the network image request for [page] — shared by [DefaultReaderPage] and the reader's
 * prefetch effect so their headers and decode limits cannot drift apart.
 */
private fun networkPageRequest(context: PlatformContext, page: Page): ImageRequest {
    val headers = NetworkHeaders.Builder().apply {
        page.headers.forEach { (name, value) -> set(name, value) }
    }.build()
    return ImageRequest.Builder(context)
        .data(page.url)
        .httpHeaders(headers)
        // per-request: loader-level cap doesn't reach decode in coil 3.5.0 (ImageLoading.kt)
        .maxBitmapSize(WebtoonMaxBitmapSize)
        .build()
}

/**
 * Default page renderer: a Coil [SubcomposeAsyncImage] carrying the page's host-required
 * headers (auth, referer) through [httpHeaders]. Plain `AsyncImage`'s placeholder slot only
 * takes a static Painter, so this uses SubcomposeAsyncImage to draw a composable loading box
 * (a tinted panel with a spinner) while a page is in flight.
 *
 * [Page] is reused for offline reading too ([ReaderScreen] fills `url` with a local absolute
 * path when the chapter is fully downloaded). Which branch runs is decided by [local] — the
 * reader's own offline state — NEVER by inspecting the url string: page URLs come from
 * untrusted extension output and must not be able to point the app at a disk path.
 */
@Composable
private fun DefaultReaderPage(page: Page, local: Boolean, aspectRatios: MutableMap<String, Float>) {
    val context = LocalPlatformContext.current
    val request = remember(page, local) {
        if (local) {
            ImageRequest.Builder(context)
                .data(File(page.url))
                .maxBitmapSize(WebtoonMaxBitmapSize)
                .build()
        } else {
            networkPageRequest(context, page)
        }
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        // pages render 800px sources into wider viewports; low (default) filtering pixelates
        filterQuality = FilterQuality.High,
        modifier = Modifier.fillMaxWidth(),
        loading = {
            // ponytail: 3:4 first-load guess, learned ratio thereafter; true first-load
            // no-reflow needs page dimensions from source metadata
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatios[page.url] ?: (3f / 4f))) {
                SkeletonBlock(modifier = Modifier.fillMaxSize())
            }
        },
        success = {
            // First reveal = this page's true size was unknown until now: crossfade in. A page
            // re-entering after recycling shows instantly — re-fading content the user already
            // saw reads as flicker while scrolling back.
            val firstReveal = remember(page) { aspectRatios[page.url] == null }
            val size = painter.intrinsicSize
            if (size.width > 0f && size.height > 0f) {
                SideEffect { aspectRatios[page.url] = size.width / size.height }
            }
            val alpha = remember(page) { Animatable(if (firstReveal) 0f else 1f) }
            LaunchedEffect(page) {
                if (firstReveal) {
                    alpha.animateTo(1f, animationSpec = tween(MangoMotion.READER_PAGE_CROSSFADE_MS))
                }
            }
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxWidth().alpha(alpha.value),
            )
        },
    )
}
