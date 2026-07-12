package dev.mango.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mango.core.domain.Download
import dev.mango.core.domain.DownloadManager
import dev.mango.core.domain.DownloadStatus

/** Pure, data-driven content — the screenshot harness renders this directly. */
@Composable
fun DownloadsScreenContent(items: List<Download>) {
    val theme = LocalMangoTheme.current
    Surface(modifier = Modifier.fillMaxSize(), color = theme.bg0) {
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No downloads yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.textSecondary,
                )
            }
            return@Surface
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            items(
                items,
                key = { "${it.sourceId}/${it.mangaId}/${it.chapterId}" },
            ) { download ->
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = download.mangaTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = theme.textPrimary,
                            )
                            Text(
                                text = "${download.sourceId} · Ch. ${formatChapterNumber(download.chapterNumber)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = theme.textSecondary,
                            )
                        }
                        when (download.status) {
                            DownloadStatus.DONE -> Text(
                                text = "Done",
                                style = MaterialTheme.typography.labelMedium,
                                color = theme.accent,
                            )
                            DownloadStatus.FAILED -> Text(
                                text = "Failed",
                                style = MaterialTheme.typography.labelMedium,
                                color = theme.danger,
                            )
                            DownloadStatus.QUEUED -> Text(
                                text = "Queued",
                                style = MaterialTheme.typography.labelMedium,
                                color = theme.textSecondary,
                            )
                            DownloadStatus.RUNNING -> Text(
                                text = "${download.pagesDone} / ${download.pagesTotal}",
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.textSecondary,
                            )
                        }
                    }
                    if (download.status == DownloadStatus.RUNNING) {
                        if (download.pagesTotal > 0) {
                            LinearProgressIndicator(
                                progress = { download.pagesDone.toFloat() / download.pagesTotal },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    HorizontalDivider(color = theme.divider)
                }
            }
        }
    }
}

/** Stateful loader: streams the live queue from [DownloadManager]. */
@Composable
fun DownloadsScreen(downloads: DownloadManager) {
    val items by downloads.observeDownloads().collectAsState(initial = emptyList())
    DownloadsScreenContent(items = items)
}
