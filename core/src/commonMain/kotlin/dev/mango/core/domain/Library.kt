package dev.mango.core.domain

import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/** A series the user has saved, normalized at the persistence boundary. No infrastructure here. */
data class LibraryItem(val entry: MangaEntry, val addedAt: Instant)

/** Where the user left off in one chapter. */
data class ReadProgress(val chapterId: String, val page: Int, val updatedAt: Instant, val finished: Boolean = false)

/**
 * Persistence contract for the user's library and reading progress. Nothing here may depend
 * on how storage is implemented (no SQLDelight, no DB types).
 */
interface LibraryRepository {
    fun observeLibrary(): Flow<List<LibraryItem>>
    suspend fun addToLibrary(entry: MangaEntry)
    suspend fun removeFromLibrary(sourceId: String, mangaId: String)
    suspend fun progress(sourceId: String, mangaId: String, chapterId: String): ReadProgress?
    suspend fun setProgress(sourceId: String, mangaId: String, chapterId: String, page: Int, finished: Boolean = false)
    /** Chapter ids the user has read to the last page — an opened-but-unfinished chapter is NOT in this set. */
    suspend fun finishedChapterIds(sourceId: String, mangaId: String): Set<String>
    /** The most recently updated progress row for the manga, finished or not; null if never opened. */
    suspend fun latestProgress(sourceId: String, mangaId: String): ReadProgress?
}
