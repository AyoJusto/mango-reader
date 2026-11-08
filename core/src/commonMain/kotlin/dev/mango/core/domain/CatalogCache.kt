package dev.mango.core.domain

import kotlin.time.Instant

/** A cached details render; a chapter id absent from [firstSeenAt] predates the cache entry. */
data class CachedManga(
    val details: MangaDetails,
    val chapters: List<Chapter>,
    val checkedAt: Instant = Instant.fromEpochMilliseconds(0),
    val firstSeenAt: Map<String, Instant> = emptyMap(),
)

/** Render cache for the details screen; hits may be stale, so callers must revalidate. */
interface CatalogCache {
    suspend fun get(sourceId: String, mangaId: String): CachedManga?

    /** Replaces the cached details and the manga's entire chapter set atomically. */
    suspend fun put(sourceId: String, mangaId: String, details: MangaDetails, chapters: List<Chapter>)
}
