package dev.mango.core.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.mango.core.db.MangoDatabase
import dev.mango.core.domain.CollectionInfo
import dev.mango.core.domain.LibraryItem
import dev.mango.core.domain.LibraryRepository
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.ReadProgress
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** SQLDelight-backed [LibraryRepository]. The only place in :core allowed to know about the DB. */
class SqlLibraryRepository(
    private val db: MangoDatabase,
    private val context: CoroutineContext = Dispatchers.Default,
    private val clock: Clock = Clock.System,
) : LibraryRepository {
    override fun observeLibrary(): Flow<List<LibraryItem>> =
        db.libraryQueries.selectAllLibraryItems().asFlow().mapToList(context).map { rows ->
            rows.map { row ->
                LibraryItem(
                    entry = MangaEntry(
                        sourceId = row.source_id,
                        mangaId = row.manga_id,
                        title = row.title,
                        cover = row.cover,
                    ),
                    addedAt = Instant.fromEpochMilliseconds(row.added_at),
                    chapterCount = row.chapter_count.toInt(),
                    unreadCount = (row.unread_count ?: 0L).toInt(),
                    lastReadAt = row.last_read_at?.let { Instant.fromEpochMilliseconds(it) },
                    newCount = row.new_count.toInt(),
                    lastOpenedAt = row.last_opened_at.takeIf { it != 0L }?.let { Instant.fromEpochMilliseconds(it) },
                    collectionIds = row.collection_ids
                        .takeIf { it.isNotEmpty() }
                        ?.split(',')?.map { it.toLong() }?.toSet()
                        ?: emptySet(),
                )
            }
        }

    override suspend fun addToLibrary(entry: MangaEntry) = withContext(context) {
        db.transaction {
            // Filing into the default happens only when the series was absent: this call also
            // fires on every chapter download, and a series the user deliberately unfiled from
            // the default shelf must not be re-filed by the next download.
            val alreadyInLibrary =
                db.libraryQueries.libraryItemExists(entry.sourceId, entry.mangaId).executeAsOne()
            db.libraryQueries.upsertLibraryItem(
                source_id = entry.sourceId,
                manga_id = entry.mangaId,
                title = entry.title,
                cover = entry.cover,
                added_at = clock.now().toEpochMilliseconds(),
            )
            if (!alreadyInLibrary) {
                db.collectionsQueries.insertMember(
                    collection_id = defaultCollectionId(),
                    source_id = entry.sourceId,
                    manga_id = entry.mangaId,
                )
            }
        }
    }

    override suspend fun removeFromLibrary(sourceId: String, mangaId: String) = withContext(context) {
        db.transaction {
            db.libraryQueries.deleteLibraryItem(source_id = sourceId, manga_id = mangaId)
            db.collectionsQueries.deleteMembersOfSeries(source_id = sourceId, manga_id = mangaId)
        }
    }

    override suspend fun progress(sourceId: String, mangaId: String, chapterId: String): ReadProgress? =
        withContext(context) {
            db.libraryQueries.selectProgress(sourceId, mangaId, chapterId).executeAsOneOrNull()?.let { row ->
                ReadProgress(
                    chapterId = row.chapter_id,
                    page = row.page.toInt(),
                    updatedAt = Instant.fromEpochMilliseconds(row.updated_at),
                    finished = row.finished != 0L,
                    chapterNumber = row.chapter_number,
                )
            }
        }

    override suspend fun setProgress(
        sourceId: String,
        mangaId: String,
        chapterId: String,
        page: Int,
        finished: Boolean,
        chapterNumber: Double,
    ) = withContext(context) {
        db.libraryQueries.upsertProgress(
            source_id = sourceId,
            manga_id = mangaId,
            chapter_id = chapterId,
            page = page.toLong(),
            finished = if (finished) 1L else 0L,
            updated_at = clock.now().toEpochMilliseconds(),
            chapter_number = chapterNumber,
        )
        Unit
    }

    override suspend fun finishedChapterIds(sourceId: String, mangaId: String): Set<String> = withContext(context) {
        db.libraryQueries.selectFinishedChapterIds(sourceId, mangaId).executeAsList().toSet()
    }

    override suspend fun latestProgress(sourceId: String, mangaId: String): ReadProgress? = withContext(context) {
        db.libraryQueries.selectLatestProgress(sourceId, mangaId).executeAsOneOrNull()?.let { row ->
            ReadProgress(
                chapterId = row.chapter_id,
                page = row.page.toInt(),
                updatedAt = Instant.fromEpochMilliseconds(row.updated_at),
                finished = row.finished != 0L,
                chapterNumber = row.chapter_number,
            )
        }
    }

    override suspend fun setChapterCount(sourceId: String, mangaId: String, count: Int) = withContext(context) {
        db.libraryQueries.updateChapterCount(chapter_count = count.toLong(), source_id = sourceId, manga_id = mangaId)
        Unit
    }

    override suspend fun markOpened(sourceId: String, mangaId: String) = withContext(context) {
        db.libraryQueries.markOpened(
            last_opened_at = clock.now().toEpochMilliseconds(),
            source_id = sourceId,
            manga_id = mangaId
        )
        Unit
    }

    override fun observeCollections(): Flow<List<CollectionInfo>> =
        db.collectionsQueries.selectAllCollections().asFlow().mapToList(context).map { rows ->
            rows.map { row ->
                CollectionInfo(
                    id = row.id,
                    name = row.name,
                    position = row.position.toInt(),
                    isDefault = row.is_default != 0L,
                )
            }
        }

    override suspend fun createCollection(name: String): Long = withContext(context) {
        db.transactionWithResult {
            require(db.collectionsQueries.selectByName(name).executeAsOneOrNull() == null) {
                "collection \"$name\" already exists"
            }
            val nextPosition = db.collectionsQueries.selectMaxPosition().executeAsOne() + 1
            db.collectionsQueries.insertCollection(name = name, position = nextPosition, is_default = 0)
            db.collectionsQueries.lastInsertRowId().executeAsOne()
        }
    }

    override suspend fun renameCollection(id: Long, name: String) = withContext(context) {
        db.transaction {
            val existing = db.collectionsQueries.selectByName(name).executeAsOneOrNull()
            require(existing == null || existing.id == id) { "collection \"$name\" already exists" }
            db.collectionsQueries.renameCollection(name = name, id = id)
        }
    }

    override suspend fun deleteCollection(id: Long) = withContext(context) {
        db.transaction {
            check(db.collectionsQueries.countCollections().executeAsOne() > 1L) {
                "cannot delete the last collection"
            }
            val wasDefault = db.collectionsQueries.selectDefaultId().executeAsOneOrNull() == id
            db.collectionsQueries.deleteMembersOfCollection(id)
            db.collectionsQueries.deleteCollection(id)
            if (wasDefault) {
                db.collectionsQueries.setDefault(db.collectionsQueries.selectFirstByPosition().executeAsOne().id)
            }
        }
    }

    override suspend fun reorderCollections(orderedIds: List<Long>) = withContext(context) {
        db.transaction {
            orderedIds.forEachIndexed { index, id ->
                db.collectionsQueries.updatePosition(position = index.toLong(), id = id)
            }
        }
    }

    override suspend fun setDefaultCollection(id: Long) = withContext(context) {
        db.transaction {
            db.collectionsQueries.clearDefault()
            db.collectionsQueries.setDefault(id)
        }
    }

    override suspend fun setMembership(sourceId: String, mangaId: String, collectionIds: Set<Long>) =
        withContext(context) {
            db.transaction {
                db.collectionsQueries.deleteMembersOfSeries(source_id = sourceId, manga_id = mangaId)
                collectionIds.forEach { collectionId ->
                    db.collectionsQueries.insertMember(
                        collection_id = collectionId,
                        source_id = sourceId,
                        manga_id = mangaId,
                    )
                }
            }
        }

    /**
     * Resolves the default collection's id, healing the invariant when it's broken: promotes the
     * first-by-position collection when none is flagged, or creates "Reading" on an empty table.
     * Must run inside a transaction so two concurrent first adds cannot both create the row.
     */
    private fun defaultCollectionId(): Long {
        db.collectionsQueries.selectDefaultId().executeAsOneOrNull()?.let { return it }
        db.collectionsQueries.selectFirstByPosition().executeAsOneOrNull()?.let { first ->
            db.collectionsQueries.setDefault(first.id)
            return first.id
        }
        db.collectionsQueries.insertCollection(name = "Reading", position = 0, is_default = 1)
        return db.collectionsQueries.lastInsertRowId().executeAsOne()
    }
}
