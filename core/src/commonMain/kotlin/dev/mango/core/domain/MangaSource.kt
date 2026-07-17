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

    /**
     * [requests] with this source's own request-preparation applied — order preserved, one item
     * per input item. A per-item preparation failure degrades that item to its own input request
     * rather than dropping or failing the batch. Default: identity, for sources that need none.
     */
    suspend fun prepareImageRequests(requests: List<SourceImageRequest>): List<SourceImageRequest> = requests
}
