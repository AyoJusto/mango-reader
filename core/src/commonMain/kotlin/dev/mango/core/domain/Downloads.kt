package dev.mango.core.domain

import kotlinx.coroutines.flow.Flow

enum class DownloadStatus { QUEUED, RUNNING, DONE, FAILED }

/** One chapter's download record, carrying display fields so the UI needn't join back to the catalog. */
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

/** Queues chapters for offline download and drains the queue; re-enqueueing a FAILED chapter restarts from page zero. */
interface DownloadManager {
    fun observeDownloads(): Flow<List<Download>>
    suspend fun enqueue(entry: MangaEntry, chapter: Chapter)
    suspend fun processQueue()

    /** Absolute page paths of a fully downloaded chapter in page order, or null if not completely on disk. */
    suspend fun localPages(sourceId: String, mangaId: String, chapterId: String): List<String>?

    /** Deletes every download row and page file for the manga; chapters fall back to the live catalog. */
    suspend fun clearDownloads(sourceId: String, mangaId: String)
}
