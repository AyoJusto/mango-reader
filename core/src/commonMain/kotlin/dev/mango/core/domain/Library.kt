package dev.mango.core.domain

import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/** A saved series; unread/last-read are derived in the query layer, [newCount] counts chapters seen since markOpened. */
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

/** One shelf; exactly one collection is [isDefault], the target of one-click adds. */
data class CollectionInfo(val id: Long, val name: String, val position: Int, val isDefault: Boolean)

data class ReadProgress(
    val chapterId: String,
    val page: Int,
    val updatedAt: Instant,
    val finished: Boolean = false,
    val chapterNumber: Double = 0.0,
)

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

    /** Chapter ids read to the last page; opened-but-unfinished chapters are not included. */
    suspend fun finishedChapterIds(sourceId: String, mangaId: String): Set<String>

    /** The most recently updated progress row, finished or not; null if never opened. */
    suspend fun latestProgress(sourceId: String, mangaId: String): ReadProgress?

    /** Caches the total chapter count so the unread-count query can derive it in SQL. */
    suspend fun setChapterCount(sourceId: String, mangaId: String, count: Int)

    /** Stamps when Details was opened; chapters cached before it stop counting as new. */
    suspend fun markOpened(sourceId: String, mangaId: String)
    fun observeCollections(): Flow<List<CollectionInfo>>

    /** Appends at the end. A name that already exists (exact match) is rejected with IllegalArgumentException. */
    suspend fun createCollection(name: String): Long
    suspend fun renameCollection(id: Long, name: String)

    /** Members are unfiled, not removed; deleting the default promotes the first remaining; deleting the last throws IllegalStateException. */
    suspend fun deleteCollection(id: Long)

    /** [orderedIds] must be the complete id set; positions become list indexes. */
    suspend fun reorderCollections(orderedIds: List<Long>)
    suspend fun setDefaultCollection(id: Long)

    /** Replaces the series' memberships wholesale (checkbox-picker semantics). */
    suspend fun setMembership(sourceId: String, mangaId: String, collectionIds: Set<Long>)
}
