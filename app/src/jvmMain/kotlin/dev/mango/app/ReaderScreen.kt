package dev.mango.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.ChallengeRequiredException
import dev.mango.core.domain.ChallengeSolver
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.DownloadManager
import dev.mango.core.domain.LibraryRepository
import dev.mango.core.domain.Page
import dev.mango.core.domain.SourceHeaderPolicy
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val PAGE_SCROLL_FRACTION = 0.9f

private val CONTROLS_AUTO_HIDE_MS = MangoMotion().readerIdleMs.toLong()

// Matches PREFETCH_PAGE_COUNT so appended pages fall inside the prefetch window.
private const val AUTO_LOAD_THRESHOLD = 20

private const val PREFETCH_PAGE_COUNT = 20

// Compose desktop emits synthetic hover-moves during a stationary wheel scroll; they must not reveal.
private const val OVERLAY_MOVE_THRESHOLD_PX = 2f

internal class ReaderOverlayState {
    private var lastPointer: Offset? = null

    /** The first sample only sets a baseline and never reveals. */
    fun onPointerMove(position: Offset): Boolean {
        val last = lastPointer
        lastPointer = position
        return last != null && (position - last).getDistance() >= OVERLAY_MOVE_THRESHOLD_PX
    }
}

/** [chapter] is null when the opened chapterId isn't in the caller's list. */
private data class ChapterSegment(
    val chapter: Chapter?,
    val chapterId: String,
    val pages: List<Page>,
    val offline: Boolean,
)

private fun ChapterSegment.toReaderSegment(): ReaderSegment {
    val short = chapter?.let { "Ch. " + formatChapterNumber(it.number) } ?: "Ch. ?"
    val title = chapter?.let { realChapterTitle(it.number, it.title) }
    val full = if (title != null) "$short — $title" else short
    return ReaderSegment(chapterId = chapterId, shortLabel = short, label = full, pages = pages, offline = offline)
}

private sealed interface NextLoadState {
    data object Idle : NextLoadState
    data object Loading : NextLoadState
    data class Failed(val message: String, val challengeUrl: String?) : NextLoadState
}

/** Throws on failure, including [ChallengeRequiredException]. */
private suspend fun loadChapterPages(
    sourceId: String,
    mangaId: String,
    chapterId: String,
    catalog: CatalogRepository,
    downloads: DownloadManager,
    headerPolicy: SourceHeaderPolicy?,
): Pair<List<Page>, Boolean> {
    val local = downloads.localPages(sourceId, mangaId, chapterId)
    return if (local != null) {
        local.mapIndexed { index, path -> Page(index = index, url = path) } to true
    } else {
        val pages = catalog.pages(sourceId, mangaId, chapterId)
        (headerPolicy?.withPolicyHeaders(sourceId, pages) ?: pages) to false
    }
}

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
    controlsAutoHideMillis: Long = CONTROLS_AUTO_HIDE_MS,
    autoScrollSpeedDpPerSec: Float = 120f,
    stripWidthDp: Float = DEFAULT_STRIP_WIDTH_DP,
    pageContent: (@Composable (Page) -> Unit)? = null,
    headerPolicy: SourceHeaderPolicy? = null,
    // Re-request focus when the palette closes, or the reader's shortcuts stay dead.
    paletteVisible: Boolean = false,
    hideCursorInReader: Boolean = true,
) {
    var anchorChapterId by remember(sourceId, mangaId, chapterId) { mutableStateOf(chapterId) }
    var savedPage by remember(sourceId, mangaId, anchorChapterId) { mutableStateOf<Int?>(null) }
    var error by remember(sourceId, mangaId, anchorChapterId) { mutableStateOf<String?>(null) }
    var challengeUrl by remember(sourceId, mangaId, anchorChapterId) { mutableStateOf<String?>(null) }
    var solving by remember(sourceId, mangaId, anchorChapterId) { mutableStateOf(false) }
    var reloadKey by remember(sourceId, mangaId, anchorChapterId) { mutableStateOf(0) }
    var segments by remember(sourceId, mangaId, anchorChapterId) { mutableStateOf<List<ChapterSegment>>(emptyList()) }
    var nextLoad by remember(sourceId, mangaId, anchorChapterId) { mutableStateOf<NextLoadState>(NextLoadState.Idle) }
    var solvingNext by remember(sourceId, mangaId, anchorChapterId) { mutableStateOf(false) }
    // Fresh scroll state per anchor so no frame renders the old chapter's offset before restore.
    val listState = remember(sourceId, mangaId, anchorChapterId) { LazyListState() }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var revealVersion by remember { mutableStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }
    // Unkeyed on chapter identity so auto-appending a chapter doesn't stop the scroll.
    var autoScrolling by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val overlayState = remember { ReaderOverlayState() }
    val platformContext = LocalPlatformContext.current
    // Hoisted so recycled pages reserve their real height; a collapsed page breaks upward scroll.
    val pageAspectRatios = remember { mutableStateMapOf<String, Float>() }

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
            val (loadedPages, isOffline) =
                loadChapterPages(sourceId, mangaId, next.chapterId, catalog, downloads, headerPolicy)
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

    // Stops auto-scroll here: a re-anchor mints a fresh listState the old drive loop would miss.
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
            val (loadedPages, isOffline) =
                loadChapterPages(sourceId, mangaId, anchorChapterId, catalog, downloads, headerPolicy)
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

    // Restore before observing so the debounced writer never sees a spurious 0.
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

    // Written immediately: debounce would coalesce away the transient finished window.
    LaunchedEffect(firstSegmentChapterId) {
        if (firstSegmentChapterId == null) return@LaunchedEffect
        val written = mutableSetOf<String>()
        snapshotFlow {
            val rows = currentRows()
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?: return@snapshotFlow emptySet()
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

    // Custom pageContent (tests) must never trigger network enqueues.
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

    // Delay-based, not polling, so the Compose test clock can advance it.
    LaunchedEffect(revealVersion, paletteVisible) {
        controlsVisible = true
        if (paletteVisible) return@LaunchedEffect
        delay(controlsAutoHideMillis.milliseconds)
        controlsVisible = false
    }

    // Pauses while the palette owns the keyboard, since A can't stop the strip.
    LaunchedEffect(autoScrolling, autoScrollSpeedDpPerSec, paletteVisible) {
        if (!autoScrolling || paletteVisible) return@LaunchedEffect
        val speedPx = with(density) { autoScrollSpeedDpPerSec.dp.toPx() }
        var lastFrameNanos = -1L
        while (autoScrolling) {
            val frameNanos = withFrameNanos { it }
            if (lastFrameNanos >= 0) {
                val delta = speedPx * (frameNanos - lastFrameNanos) / 1_000_000_000f
                if (delta > 0f && listState.scrollBy(delta) == 0f) {
                    // A still-loading next chapter keeps the loop alive; stop at the last chapter or a failed append.
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
            // Focus must be requested here: the focusRequester is only attached at this point.
            LaunchedEffect(paletteVisible) { if (!paletteVisible) focusRequester.requestFocus() }
            // Cursor hides with the overlay; only hiding depends on hideCursorInReader.
            HideCursorWhile(hidden = hideCursorInReader && !controlsVisible && !paletteVisible)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPointerEvent(PointerEventType.Move) { event ->
                        val position = event.changes.firstOrNull()?.position
                        if (position != null && overlayState.onPointerMove(position)) {
                            revealVersion++
                        }
                    }
                    .onPointerEvent(PointerEventType.Press) { event ->
                        // Presses on overlay controls arrive already consumed and must not toggle.
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
                    // Local-file loading is gated on our offline state, never on the untrusted page.url.
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

private fun networkPageRequest(context: PlatformContext, page: Page): ImageRequest =
    ImageRequest.Builder(context)
        .data(page.url)
        .policyHeaders(page.headers)
        // Per-request: the loader-level cap doesn't reach decode in coil 3.5.0.
        .maxBitmapSize(WebtoonMaxBitmapSize)
        .build()

/** Branches on [local], never the untrusted page url; the error slot reserves the loading height. */
@Composable
private fun DefaultReaderPage(page: Page, local: Boolean, aspectRatios: MutableMap<String, Float>) {
    val context = LocalPlatformContext.current
    val theme = LocalMangoTheme.current
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
        // Low default filtering pixelates 800px sources in wider viewports.
        filterQuality = FilterQuality.High,
        modifier = Modifier.fillMaxWidth(),
        loading = {
            // 3:4 until the first image loads; sources don't report page dimensions.
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatios[page.url] ?: (3f / 4f))) {
                SkeletonBlock(modifier = Modifier.fillMaxSize())
            }
        },
        error = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatios[page.url] ?: (3f / 4f))
                    .background(theme.bg1)
                    .testTag("reader-page-error"),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "page failed to load", style = MangoType.caption, color = theme.textTertiary)
            }
        },
        success = {
            val firstReveal = remember(page) { aspectRatios[page.url] == null }
            val size = painter.intrinsicSize
            if (size.width > 0f && size.height > 0f) {
                SideEffect { aspectRatios[page.url] = size.width / size.height }
            }
            val motion = LocalMangoMotion.current
            val alpha = remember(page) { Animatable(if (firstReveal) 0f else 1f) }
            LaunchedEffect(page) {
                if (firstReveal) {
                    alpha.animateTo(1f, animationSpec = tween(motion.readerPageCrossfadeMs))
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
