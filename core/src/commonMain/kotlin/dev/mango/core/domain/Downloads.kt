package dev.mango.core.domain

import kotlinx.coroutines.flow.Flow

/** Where a queued chapter download currently stands. */
enum class DownloadStatus { QUEUED, RUNNING, DONE, FAILED }

/**
 * One chapter's download record: queue state plus page-count progress, plus the display fields
 * (mangaTitle, chapterNumber) the downloads screen needs — carried on the row itself so the UI
 * never has to join back to the catalog for a manga that may no longer be reachable. No
 * infrastructure here.
 */
data class Download(
    val sourceId: String,
    val mangaId: String,
    val chapterId: String,
    val mangaTitle: String,
    val chapterNumber: Double,
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
    suspend fun enqueue(entry: MangaEntry, chapter: Chapter)
    suspend fun processQueue()

    /**
     * Absolute file paths of a fully downloaded chapter's pages, in page order — or null when
     * the chapter is not completely on disk (no row, not DONE, or files missing). Strings, not
     * Path: the domain stays infrastructure-free.
     */
    suspend fun localPages(sourceId: String, mangaId: String, chapterId: String): List<String>?
}
