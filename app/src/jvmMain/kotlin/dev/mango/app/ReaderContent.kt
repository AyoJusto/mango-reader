package dev.mango.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.Page

internal const val DEFAULT_STRIP_WIDTH_DP = 880f

data class ReaderSegment(
    val chapterId: String,
    val shortLabel: String,
    val label: String,
    val pages: List<Page>,
    val offline: Boolean = false,
)

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

internal fun buildRows(
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

/** Offline segments are skipped without consuming the count. */
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

internal data class ReaderPosition(val segmentIndex: Int, val pageIndex: Int)

/** Divider and tail rows map to the preceding segment, never marking the next chapter read. */
internal fun currentPosition(rows: List<ReaderRow>, flattenedIndex: Int): ReaderPosition? {
    if (rows.isEmpty()) return null
    val clamped = flattenedIndex.coerceIn(0, rows.lastIndex)
    for (i in clamped downTo 0) {
        val row = rows[i]
        if (row is ReaderRow.PageRow) return ReaderPosition(row.segmentIndex, row.page.index)
    }
    return null
}

internal fun nextChapter(chapters: List<Chapter>, currentChapterId: String): Chapter? {
    val index = chapters.indexOfFirst { it.chapterId == currentChapterId }
    if (index == -1 || index == chapters.lastIndex) return null
    return chapters[index + 1]
}

internal fun previousChapter(chapters: List<Chapter>, currentChapterId: String): Chapter? {
    val index = chapters.indexOfFirst { it.chapterId == currentChapterId }
    if (index <= 0) return null
    return chapters[index - 1]
}

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
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
    onRetryNext: () -> Unit = {},
    onSolveNextChallenge: () -> Unit = {},
    onToggleAutoScroll: () -> Unit = {},
    nextChallengeSolving: Boolean = false,
    pageContent: @Composable (Page, Boolean) -> Unit,
) {
    val theme = LocalMangoTheme.current
    val motion = LocalMangoMotion.current
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
                enter = fadeIn(animationSpec = tween(motion.readerOverlayInMs, easing = motion.decel)),
                exit = fadeOut(animationSpec = tween(motion.readerOverlayOutMs, easing = motion.standard)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
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

/** The "‹ Prev" / "Next ›" labels are matched by flow tests. */
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
            .padding(vertical = MangoSpace.sm, horizontal = MangoSpace.md)
            .semantics {
                if (pageNumber != null && pageCount != null) contentDescription = "Page $pageNumber of $pageCount"
            },
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
                if (offline) {
                    Text(text = "offline", style = MangoType.meta, color = theme.textSecondary)
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
            .keyboardFocusRing(RoundedCornerShape(8.dp))
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

@Composable
private fun AutoScrollPill(active: Boolean, onClick: () -> Unit) {
    val theme = LocalMangoTheme.current
    Row(
        modifier = Modifier
            .height(28.dp)
            .keyboardFocusRing(RoundedCornerShape(MangoRadius.pill))
            .clip(RoundedCornerShape(MangoRadius.pill))
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

/** The "Solve challenge" and "Retry" labels are matched by flow tests. */
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
            Box(modifier = Modifier.widthIn(max = 420.dp)) {
                ChallengeErrorContent(
                    error = message,
                    challengeUrl = challengeUrl,
                    solving = solving,
                    solveEnabled = !solving,
                    onSolveChallenge = onSolveChallenge,
                    onRetry = onRetry,
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MangoSpace.sm)
            ) {
                Text(text = message, style = MangoType.bodyStrong, color = theme.danger)
                KitButton(label = "Retry", onClick = onRetry, style = KitButtonStyle.DANGER, enabled = !solving)
            }
        }
    }
}
