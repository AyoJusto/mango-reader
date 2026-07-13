package dev.mango.core.domain

import kotlin.time.Instant

/**
 * A cached details render: the manga's detail fields, its full chapter list, when the entry was
 * last written ([checkedAt]), and when each chapter first appeared in the cache ([firstSeenAt],
 * keyed by chapterId). A chapter id absent from [firstSeenAt] predates this cache entirely.
 */
data class CachedManga(
    val details: MangaDetails,
    val chapters: List<Chapter>,
    val checkedAt: Instant = Instant.fromEpochMilliseconds(0),
    val firstSeenAt: Map<String, Instant> = emptyMap(),
)

/**
 * Persistent render cache for the details screen. A hit lets the UI paint the last-known
 * details and chapter list immediately, before any live fetch completes; callers must treat
 * the content as possibly stale and revalidate it themselves. [CachedManga.checkedAt] says only
 * when the entry was last written, not whether it is still accurate; [CachedManga.firstSeenAt]
 * is the basis for telling a newly-arrived chapter from one the cache has always known about.
 */
interface CatalogCache {
    suspend fun get(sourceId: String, mangaId: String): CachedManga?

    /** Replaces the cached details and the manga's entire chapter set atomically. */
    suspend fun put(sourceId: String, mangaId: String, details: MangaDetails, chapters: List<Chapter>)
}
