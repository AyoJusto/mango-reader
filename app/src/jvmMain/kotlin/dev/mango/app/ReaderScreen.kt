package dev.mango.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlin.math.roundToInt
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

/** The reading strip's default width — the spec's literal value; a user-facing width slider is not wired yet. */
private const val DEFAULT_STRIP_WIDTH_DP = 880f

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

/** One row in the flattened, multi-chapter LazyColumn strip. Internal: a rendering detail shared with the prefetch helper below. */
internal sealed interface ReaderRow {
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

/**
 * The next up-to-[count] pages strictly after [lastVisibleIndex] in [rows], for network
 * prefetch. Pages whose segment is offline are skipped (local files need no prefetch) without
 * consuming the count.
 */
internal fun pagesToPrefetch(
    rows: List<ReaderRow>,
    segments: List<ReaderSegment>,
    lastVisibleIndex: Int,
    count: Int,
): List<Page> =
    rows.asSequence()
        .drop((lastVisibleIndex + 1).coerceAtLeast(0))
        .filterIsInstance<ReaderRow.PageRow>()
        .filterNot { segments.getOrNull(it.segmentIndex)?.offline ?: true }
        .map { it.page }
        .take(count)
        .toList()

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
 * Full-bleed pages of every loaded chapter in one [LazyColumn] on [LocalMangoTheme]'s `bg0`, centered at a
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
    autoScrolling: Boolean = false,
    stripWidthDp: Float = DEFAULT_STRIP_WIDTH_DP,
    onBack: () -> Unit,
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
    onRetryNext: () -> Unit = {},
    onSolveNextChallenge: () -> Unit = {},
    onToggleAutoScroll: () -> Unit = {},
    nextChallengeSolving: Boolean = false,
    pageContent: @Composable (Page, Boolean) -> Unit,
) {
    val theme = LocalMangoTheme.current
    val rows = remember(segments, isLastChapter, nextLoading, nextError, nextChallengeUrl) {
        buildRows(segments, isLastChapter, nextLoading, nextError, nextChallengeUrl)
    }
    val position = currentPosition(rows, listState.firstVisibleItemIndex)
    val currentSegment = position?.let { segments.getOrNull(it.segmentIndex) } ?: segments.firstOrNull()
    val currentChapterId = currentSegment?.chapterId
    val prevEnabled = currentChapterId != null && previousChapter(chapters, currentChapterId) != null
    val nextEnabled = currentChapterId != null && nextChapter(chapters, currentChapterId) != null
    val pageNumber = position?.pageIndex?.plus(1)
    val pageCount = currentSegment?.pages?.size?.takeIf { it > 0 }
    val progressFraction = if (pageNumber != null && pageCount != null) pageNumber.toFloat() / pageCount else 0f

    Surface(modifier = Modifier.fillMaxSize(), color = theme.bg0) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxHeight()
                    .widthIn(max = stripWidthDp.dp)
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
                enter = fadeIn(animationSpec = tween(MangoMotion.READER_OVERLAY_IN_MS, easing = MangoMotion.decel)),
                exit = fadeOut(animationSpec = tween(MangoMotion.READER_OVERLAY_OUT_MS, easing = MangoMotion.standard)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    ReaderBackButton(
                        onClick = onBack,
                        modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                    )
                    ReaderOverlayBar(
                        label = currentSegment?.label.orEmpty(),
                        pageNumber = pageNumber,
                        pageCount = pageCount,
                        offline = currentSegment?.offline == true,
                        progress = progressFraction,
                        prevEnabled = prevEnabled,
                        nextEnabled = nextEnabled,
                        onPrev = onPrev,
                        onNext = onNext,
                        autoScrolling = autoScrolling,
                        onToggleAutoScroll = onToggleAutoScroll,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                    )
                }
            }
        }
    }
}

/** Top-left exit control, per board 05: overlay-token fill, a leading chevron, wired to [onBack]. */
@Composable
private fun ReaderBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalMangoTheme.current
    Box(
        modifier = modifier
            .size(width = 34.dp, height = 30.dp)
            .clip(RoundedCornerShape(MangoRadius.control))
            .background(theme.overlay)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = Icons.Filled.ChevronLeft, contentDescription = "Back", tint = theme.textPrimary)
    }
}

/**
 * Bottom-centered floating status bar, per board 05: chapter title and panel progress on the
 * left, chapter nav and the auto-scroll toggle on the right, a thin progress track below.
 *
 * The nav buttons keep their historical "‹ Prev" / "Next ›" text (rather than icon-only glyphs)
 * because existing flow tests locate them by that exact text; only the surrounding chrome is new.
 */
@Composable
private fun ReaderOverlayBar(
    label: String,
    pageNumber: Int?,
    pageCount: Int?,
    offline: Boolean,
    progress: Float,
    prevEnabled: Boolean,
    nextEnabled: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    autoScrolling: Boolean,
    onToggleAutoScroll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalMangoTheme.current
    Column(
        modifier = modifier
            .width(520.dp)
            .shadow(elevation = 16.dp, shape = RoundedCornerShape(MangoRadius.panel))
            .clip(RoundedCornerShape(MangoRadius.panel))
            .background(theme.overlay)
            .padding(vertical = MangoSpace.sm, horizontal = MangoSpace.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MangoType.bodyStrong,
                    color = theme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (pageNumber != null && pageCount != null) {
                    Row {
                        Text(
                            text = "$pageNumber / $pageCount" + if (offline) " · offline" else "",
                            fontSize = 11.5.sp,
                            color = theme.textSecondary,
                        )
                        if (!offline) {
                            Text(
                                text = " panels · ${(progress * 100).roundToInt()}%",
                                fontSize = 11.5.sp,
                                color = theme.textSecondary,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.width(MangoSpace.sm))
            ReaderChapterNavButton(label = "‹ Prev", enabled = prevEnabled, onClick = onPrev)
            Spacer(modifier = Modifier.width(6.dp))
            ReaderChapterNavButton(label = "Next ›", enabled = nextEnabled, onClick = onNext)
            Spacer(modifier = Modifier.width(6.dp))
            AutoScrollPill(active = autoScrolling, onClick = onToggleAutoScroll)
        }
        Spacer(modifier = Modifier.height(MangoSpace.sm))
        ProgressTrack(
            progress = progress,
            trackColor = theme.textPrimary.copy(alpha = 0.10f),
            height = 3.dp,
            // Reaching the strip's bottom is just a position, not a completion state.
            successAtFull = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ReaderChapterNavButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val theme = LocalMangoTheme.current
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(theme.textPrimary.copy(alpha = 0.06f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MangoType.caption,
            color = if (enabled) theme.textPrimary else theme.textPrimary.copy(alpha = 0.3f),
        )
    }
}

/** Wires to the existing auto-scroll toggle; a leading dot appears only while it's running. */
@Composable
private fun AutoScrollPill(active: Boolean, onClick: () -> Unit) {
    val theme = LocalMangoTheme.current
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(theme.accent.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (active) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(theme.accent))
        }
        Text(text = "Auto-scroll", style = MangoType.caption, color = theme.accent)
    }
}

@Composable
private fun ChapterDividerRow(fromLabel: String, toLabel: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "$fromLabel — end · $toLabel",
            style = MaterialTheme.typography.labelMedium,
            color = LocalMangoTheme.current.textSecondary,
        )
    }
}

@Composable
private fun EndOfStripRow() {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "No more chapters",
            style = MaterialTheme.typography.bodyMedium,
            color = LocalMangoTheme.current.textSecondary,
        )
    }
}

@Composable
private fun LoadingTailRow() {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * The next-chapter load failed. A Cloudflare challenge reads as warning (per the challenge
 * grammar in ChallengeUi.kt — the user did nothing wrong); any other failure stays danger. The
 * "Solve challenge" and "Retry" button labels are exact — flow tests locate them by that text.
 */
@Composable
private fun FailedTailRow(
    message: String,
    challengeUrl: String?,
    onRetry: () -> Unit,
    onSolveChallenge: () -> Unit,
    solving: Boolean = false,
) {
    val theme = LocalMangoTheme.current
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        if (challengeUrl != null) {
            Column(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .clip(RoundedCornerShape(MangoRadius.row))
                    .background(theme.warning.copy(alpha = 0.10f))
                    .padding(MangoSpace.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MangoSpace.sm),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm)) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(theme.warning))
                    Text(text = message, style = MangoType.bodyStrong, color = theme.textPrimary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm)) {
                    KitButton(label = "Solve challenge", onClick = onSolveChallenge, style = KitButtonStyle.PRIMARY, enabled = !solving)
                    KitButton(label = "Retry", onClick = onRetry, style = KitButtonStyle.SECONDARY, enabled = !solving)
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(MangoSpace.sm)) {
                Text(text = message, style = MangoType.bodyStrong, color = theme.danger)
                KitButton(label = "Retry", onClick = onRetry, style = KitButtonStyle.DANGER, enabled = !solving)
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
    // Bumped on every genuine reveal (a pointer move past ReaderOverlayState's delta gate, or
    // any click); the effect below (keyed on this) is what actually schedules the hide.
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
            .debounce(progressDebounceMillis)
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
        delay(controlsAutoHideMillis)
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
                    // controlsVisible flips back (never leaks past this Box's lifetime).
                    .pointerHoverIcon(if (controlsVisible) PointerIcon.Default else BLANK_CURSOR_ICON, overrideDescendants = true)
                    .onPointerEvent(PointerEventType.Move) { event ->
                        val position = event.changes.firstOrNull()?.position
                        // Keyboard paging/N/P never reach this handler — only a genuine pointer
                        // move (gated by ReaderOverlayState) or a click reveals.
                        if (position != null && overlayState.onPointerMove(position)) {
                            revealVersion++
                        }
                    }
                    .onPointerEvent(PointerEventType.Press) {
                        // A click always reveals.
                        revealVersion++
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
 * takes a static Painter, so this uses SubcomposeAsyncImage to draw a composable loading box
 * (a tinted panel with a spinner) while a page is in flight.
 *
 * [Page] is reused for offline reading too ([ReaderScreen] fills `url` with a local absolute
 * path when the chapter is fully downloaded). Which branch runs is decided by [local] — the
 * reader's own offline state — NEVER by inspecting the url string: page URLs come from
 * untrusted extension output and must not be able to point the app at a disk path.
 */
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

@Composable
private fun DefaultReaderPage(page: Page, local: Boolean) {
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
            // ponytail: 3:4 reserved height; true no-reflow needs page dimensions from source metadata
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
                SkeletonBlock(modifier = Modifier.fillMaxSize())
            }
        },
        success = {
            val alpha = remember(page) { Animatable(0f) }
            LaunchedEffect(page) {
                alpha.animateTo(1f, animationSpec = tween(MangoMotion.READER_PAGE_CROSSFADE_MS))
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
