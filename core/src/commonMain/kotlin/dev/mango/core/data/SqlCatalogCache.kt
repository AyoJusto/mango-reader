package dev.mango.core.data

import dev.mango.core.db.MangoDatabase
import dev.mango.core.domain.CachedManga
import dev.mango.core.domain.CatalogCache
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.MangaStatus
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** SQLDelight-backed [CatalogCache]. The only place in :core allowed to know about the DB. */
class SqlCatalogCache(
    private val db: MangoDatabase,
    private val context: CoroutineContext = Dispatchers.Default,
    private val clock: Clock = Clock.System,
) : CatalogCache {
    override suspend fun get(sourceId: String, mangaId: String): CachedManga? = withContext(context) {
        val detailsRow = db.catalog_cacheQueries.selectDetailsCache(sourceId, mangaId).executeAsOneOrNull()
            ?: return@withContext null

        val chapterRows = db.catalog_cacheQueries.selectChapterCache(sourceId, mangaId).executeAsList()
        val chapters = chapterRows.map { row ->
            Chapter(
                chapterId = row.chapter_id,
                number = row.number,
                title = row.title,
                publishedAt = row.published_at?.let { Instant.fromEpochMilliseconds(it) },
            )
        }

        CachedManga(
            details = MangaDetails(
                entry = MangaEntry(
                    sourceId = detailsRow.source_id,
                    mangaId = detailsRow.manga_id,
                    title = detailsRow.title,
                    cover = detailsRow.cover,
                ),
                authors = splitList(detailsRow.authors),
                description = detailsRow.description,
                status = MangaStatus.valueOf(detailsRow.status),
                tags = splitList(detailsRow.tags),
            ),
            chapters = chapters,
            checkedAt = Instant.fromEpochMilliseconds(detailsRow.updated_at),
            firstSeenAt = chapterRows.associate { it.chapter_id to Instant.fromEpochMilliseconds(it.first_seen_at) },
        )
    }

    override suspend fun put(
        sourceId: String,
        mangaId: String,
        details: MangaDetails,
        chapters: List<Chapter>,
    ): Unit = withContext(context) {
        db.transaction {
            db.catalog_cacheQueries.upsertDetailsCache(
                source_id = sourceId,
                manga_id = mangaId,
                title = details.entry.title,
                cover = details.entry.cover,
                description = details.description,
                status = details.status.name,
                authors = joinList(details.authors),
                tags = joinList(details.tags),
                updated_at = clock.now().toEpochMilliseconds(),
            )

            // read before the delete so surviving chapters keep the first_seen_at they already had
            val previousFirstSeenAt = db.catalog_cacheQueries.selectChapterFirstSeen(source_id = sourceId, manga_id = mangaId)
                .executeAsList()
                .associate { it.chapter_id to it.first_seen_at }
            // no prior rows at all means this is the manga's first fill: every chapter is
            // "already there" as far as the user is concerned, so none of them count as new
            val isFirstFill = previousFirstSeenAt.isEmpty()
            val now = clock.now().toEpochMilliseconds()

            // atomic replace: delete then re-insert with fresh positions, so a shrunk chapter
            // list can't leave orphaned rows behind
            db.catalog_cacheQueries.deleteChapterCache(source_id = sourceId, manga_id = mangaId)
            chapters.forEachIndexed { index, chapter ->
                val firstSeenAt = when {
                    isFirstFill -> 0L
                    else -> previousFirstSeenAt[chapter.chapterId] ?: now
                }
                db.catalog_cacheQueries.insertChapterCache(
                    source_id = sourceId,
                    manga_id = mangaId,
                    chapter_id = chapter.chapterId,
                    number = chapter.number,
                    title = chapter.title,
                    published_at = chapter.publishedAt?.toEpochMilliseconds(),
                    position = index.toLong(),
                    first_seen_at = firstSeenAt,
                )
            }
        }
    }

    private fun joinList(values: List<String>): String = values.joinToString(LIST_DELIMITER)

    private fun splitList(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(LIST_DELIMITER)

    private companion object {
        // ASCII unit separator: cannot occur in extension-supplied author/tag text, so no
        // escaping is needed on join or split.
        const val LIST_DELIMITER = "\u001F"
    }
}
