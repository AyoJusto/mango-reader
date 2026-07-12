package dev.mango.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mango.core.domain.Download
import dev.mango.core.domain.DownloadManager
import dev.mango.core.domain.DownloadStatus

/** Pure, data-driven content — the screenshot harness renders this directly. */
@Composable
fun DownloadsScreenContent(items: List<Download>) {
    val theme = LocalMangoTheme.current
    Surface(modifier = Modifier.fillMaxSize(), color = theme.bg0) {
        ContentColumn(max = MangoSpace.contentMaxWidth) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "Downloads",
                    style = MangoType.display,
                    color = theme.textPrimary,
                    modifier = Modifier.padding(horizontal = MangoSpace.screenGutter, vertical = MangoSpace.lg),
                )
                if (items.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            title = "No downloads yet",
                            guidance = "Download chapters from Details, or press Shift-Shift to search everywhere.",
                        )
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = MangoSpace.screenGutter, vertical = MangoSpace.sm),
                        verticalArrangement = Arrangement.spacedBy(MangoSpace.xs),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(
                            items,
                            key = { "${it.sourceId}/${it.mangaId}/${it.chapterId}" },
                        ) { download ->
                            DownloadRow(download)
                        }
                    }
                }
            }
        }
    }
}

/** One queued/active/finished chapter download: bg1 card, thumb, title/chapter, state, progress. */
@Composable
private fun DownloadRow(download: Download) {
    val theme = LocalMangoTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MangoRadius.row))
            .background(theme.bg1)
            .padding(vertical = MangoSpace.sm, horizontal = MangoSpace.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 30.dp, height = 42.dp)
                    .clip(RoundedCornerShape(MangoRadius.keycap))
                    .background(theme.bg2),
            )
            Spacer(modifier = Modifier.width(MangoSpace.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = download.mangaTitle,
                    style = MangoType.bodyStrong,
                    color = theme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Ch. ${formatChapterNumber(download.chapterNumber)}",
                    style = MangoType.caption,
                    color = theme.textSecondary,
                )
            }
            Spacer(modifier = Modifier.width(MangoSpace.sm))
            DownloadStateText(download.status)
        }
        if (download.status == DownloadStatus.RUNNING || download.status == DownloadStatus.DONE) {
            Spacer(modifier = Modifier.height(MangoSpace.xs))
            val progress = if (download.status == DownloadStatus.DONE) {
                1f
            } else if (download.pagesTotal > 0) {
                download.pagesDone.toFloat() / download.pagesTotal
            } else {
                0f
            }
            ProgressTrack(
                progress = progress,
                // RUNNING must stay accent even at 100% mid-transition; DONE always reads success.
                successAtFull = download.status == DownloadStatus.DONE,
                modifier = Modifier.fillMaxWidth(),
            )
            if (download.status == DownloadStatus.RUNNING && download.pagesTotal > 0) {
                Spacer(modifier = Modifier.height(MangoSpace.base))
                Text(
                    text = "${download.pagesDone} / ${download.pagesTotal} pages",
                    fontSize = 11.5.sp,
                    color = theme.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun DownloadStateText(status: DownloadStatus) {
    val theme = LocalMangoTheme.current
    val (label, color) = when (status) {
        DownloadStatus.RUNNING -> "Downloading" to theme.accent
        DownloadStatus.QUEUED -> "Queued" to theme.textTertiary
        DownloadStatus.DONE -> "Done" to theme.success
        DownloadStatus.FAILED -> "Failed" to theme.danger
    }
    Text(text = label, style = MangoType.caption, color = color)
}

/** Stateful loader: streams the live queue from [DownloadManager]. */
@Composable
fun DownloadsScreen(downloads: DownloadManager) {
    val items by downloads.observeDownloads().collectAsState(initial = emptyList())
    DownloadsScreenContent(items = items)
}
