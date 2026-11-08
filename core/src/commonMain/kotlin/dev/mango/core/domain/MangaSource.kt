package dev.mango.core.domain

/** The agnostic source contract; nothing here may depend on how a source is implemented. */
interface MangaSource {
    val sourceId: String
    suspend fun search(query: String, page: Int = 1): List<MangaEntry>
    suspend fun getDetails(mangaId: String): MangaDetails
    suspend fun getChapters(mangaId: String): List<Chapter>
    suspend fun getPages(mangaId: String, chapterId: String): List<Page>

    /** Home/discover shelves; default none so existing implementers don't break. */
    suspend fun getHomeSections(): List<HomeSection> = emptyList()

    /** [requests] with this source's preparation applied, order preserved; a failed item degrades to its input. Default identity. */
    suspend fun prepareImageRequests(requests: List<SourceImageRequest>): List<SourceImageRequest> = requests
}
