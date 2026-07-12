package dev.mango.core.domain

/** A cached details render: the manga's detail fields plus its full chapter list. */
data class CachedManga(val details: MangaDetails, val chapters: List<Chapter>)

/**
 * Persistent render cache for the details screen. A hit lets the UI paint the last-known
 * details and chapter list immediately, before any live fetch completes; callers must treat
 * the content as possibly stale and revalidate it themselves — this cache carries no freshness
 * signal beyond presence.
 */
interface CatalogCache {
    suspend fun get(sourceId: String, mangaId: String): CachedManga?

    /** Replaces the cached details and the manga's entire chapter set atomically. */
    suspend fun put(sourceId: String, mangaId: String, details: MangaDetails, chapters: List<Chapter>)
}
