package dev.mango.core.domain

import kotlinx.coroutines.flow.Flow

/** Where a queued chapter download currently stands. */
enum class DownloadStatus { QUEUED, RUNNING, DONE, FAILED }

/** One chapter's download record: queue state plus page-count progress. No infrastructure here. */
data class Download(
    val sourceId: String,
    val mangaId: String,
    val chapterId: String,
    val status: DownloadStatus,
    val pagesTotal: Int,
    val pagesDone: Int,
)

/**
 * Queues chapters for offline download and drains that queue to disk. No infrastructure here
 * (no DB, no HTTP client, no nio) — that lives in the jvmMain implementation.
 *
 * [processQueue] drains QUEUED rows sequentially, oldest first, and is safe to call again
 * after a failure: it simply picks up wherever the queue is. Re-enqueueing a FAILED chapter
 * restarts it from page zero (existing files are overwritten) — resuming from the last
 * completed page is a known upgrade path, not implemented here.
 */
interface DownloadManager {
    fun observeDownloads(): Flow<List<Download>>
    suspend fun enqueue(sourceId: String, mangaId: String, chapterId: String)
    suspend fun processQueue()
}
