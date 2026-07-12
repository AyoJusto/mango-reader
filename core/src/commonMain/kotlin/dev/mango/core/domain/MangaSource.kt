package dev.mango.core.domain

/**
 * The agnostic source contract. Paperback is the first adapter; nothing here may depend on
 * how a source is implemented (no HTTP, no DB, no engine types).
 *
 * getPages takes mangaId too: the underlying source contract needs sourceManga.mangaId, not
 * just chapterId.
 */
interface MangaSource {
    val sourceId: String
    suspend fun search(query: String, page: Int = 1): List<MangaEntry>
    suspend fun getDetails(mangaId: String): MangaDetails
    suspend fun getChapters(mangaId: String): List<Chapter>
    suspend fun getPages(mangaId: String, chapterId: String): List<Page>

    /** Home/discover shelves (e.g. "Popular", "Latest Updates"). Default: none, so existing
     * implementers don't break. */
    suspend fun getHomeSections(): List<HomeSection> = emptyList()
}
