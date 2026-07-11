package dev.mango.core.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.mango.core.db.MangoDatabase
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
                )
            }
        }

    override suspend fun addToLibrary(entry: MangaEntry) = withContext(context) {
        db.libraryQueries.upsertLibraryItem(
            source_id = entry.sourceId,
            manga_id = entry.mangaId,
            title = entry.title,
            cover = entry.cover,
            added_at = clock.now().toEpochMilliseconds(),
        )
        Unit
    }

    override suspend fun removeFromLibrary(sourceId: String, mangaId: String) = withContext(context) {
        db.libraryQueries.deleteLibraryItem(source_id = sourceId, manga_id = mangaId)
        Unit
    }

    override suspend fun progress(sourceId: String, mangaId: String, chapterId: String): ReadProgress? =
        withContext(context) {
            db.libraryQueries.selectProgress(sourceId, mangaId, chapterId).executeAsOneOrNull()?.let { row ->
                ReadProgress(
                    chapterId = row.chapter_id,
                    page = row.page.toInt(),
                    updatedAt = Instant.fromEpochMilliseconds(row.updated_at),
                )
            }
        }

    override suspend fun setProgress(sourceId: String, mangaId: String, chapterId: String, page: Int) =
        withContext(context) {
            db.libraryQueries.upsertProgress(
                source_id = sourceId,
                manga_id = mangaId,
                chapter_id = chapterId,
                page = page.toLong(),
                updated_at = clock.now().toEpochMilliseconds(),
            )
            Unit
        }

    override suspend fun readChapterIds(sourceId: String, mangaId: String): Set<String> = withContext(context) {
        db.libraryQueries.selectReadChapterIds(sourceId, mangaId).executeAsList().toSet()
    }
}
