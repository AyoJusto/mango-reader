package dev.squidwha.core.domain

/**
 * The agnostic source contract. Paperback is the first adapter; nothing here may depend on
 * how a source is implemented (no HTTP, no DB, no engine types).
 *
 * getPages takes mangaId too: the 0.9 contract needs sourceManga.mangaId, which deviates
 * from the original PLANNING sketch deliberately (see M1.5).
 */
interface MangaSource {
    val sourceId: String
    suspend fun search(query: String, page: Int = 1): List<MangaEntry>
    suspend fun getDetails(mangaId: String): MangaDetails
    suspend fun getChapters(mangaId: String): List<Chapter>
    suspend fun getPages(mangaId: String, chapterId: String): List<Page>
}
