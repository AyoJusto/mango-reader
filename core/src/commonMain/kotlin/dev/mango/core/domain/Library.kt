package dev.mango.core.domain

import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * A series the user has saved, normalized at the persistence boundary. No infrastructure here.
 * [chapterCount] is the last count recorded via [LibraryRepository.setChapterCount];
 * [unreadCount] and [lastReadAt] are derived from it and read progress at the query layer, not
 * recomputed from a chapter list here. [newCount] is the number of cached chapters first seen
 * after [LibraryRepository.markOpened] was last called for this series. [collectionIds] is the
 * set of collections the series is filed in.
 */
data class LibraryItem(
    val entry: MangaEntry,
    val addedAt: Instant,
    val chapterCount: Int = 0,
    val unreadCount: Int = 0,
    val lastReadAt: Instant? = null,
    val newCount: Int = 0,
    val lastOpenedAt: Instant? = null,
    val collectionIds: Set<Long> = emptySet(),
)

/** One shelf. [isDefault] is true for exactly one collection — the target of one-click adds. */
data class CollectionInfo(val id: Long, val name: String, val position: Int, val isDefault: Boolean)

/** Where the user left off in one chapter. */
data class ReadProgress(
    val chapterId: String,
    val page: Int,
    val updatedAt: Instant,
    val finished: Boolean = false,
    val chapterNumber: Double = 0.0,
)

/**
 * Persistence contract for the user's library and reading progress. Nothing here may depend
 * on how storage is implemented (no SQLDelight, no DB types).
 */
interface LibraryRepository {
    fun observeLibrary(): Flow<List<LibraryItem>>
    suspend fun addToLibrary(entry: MangaEntry)
    suspend fun removeFromLibrary(sourceId: String, mangaId: String)
    suspend fun progress(sourceId: String, mangaId: String, chapterId: String): ReadProgress?
    suspend fun setProgress(
        sourceId: String,
        mangaId: String,
        chapterId: String,
        page: Int,
        finished: Boolean = false,
        chapterNumber: Double = 0.0,
    )

    /** Chapter ids the user has read to the last page — an opened-but-unfinished chapter is NOT in this set. */
    suspend fun finishedChapterIds(sourceId: String, mangaId: String): Set<String>

    /** The most recently updated progress row for the manga, finished or not; null if never opened. */
    suspend fun latestProgress(sourceId: String, mangaId: String): ReadProgress?

    /** Caches the series' total chapter count so the library's unread-count query can derive it in SQL. */
    suspend fun setChapterCount(sourceId: String, mangaId: String, count: Int)

    /** Stamps the moment the user opened Details for this series; chapters cached before this stamp stop counting as new. */
    suspend fun markOpened(sourceId: String, mangaId: String)
    fun observeCollections(): Flow<List<CollectionInfo>>

    /** Appends at the end. A name that already exists (exact match) is rejected with IllegalArgumentException. */
    suspend fun createCollection(name: String): Long
    suspend fun renameCollection(id: Long, name: String)   // same duplicate rule

    /** Members are unfiled, never removed from the library. Deleting the default promotes the first remaining
     *  collection by position. Deleting the last collection is rejected with IllegalStateException. */
    suspend fun deleteCollection(id: Long)

    /** [orderedIds] must be the complete id set; positions become list indexes. */
    suspend fun reorderCollections(orderedIds: List<Long>)
    suspend fun setDefaultCollection(id: Long)

    /** Replaces the series' memberships wholesale (checkbox-picker semantics). */
    suspend fun setMembership(sourceId: String, mangaId: String, collectionIds: Set<Long>)
}
