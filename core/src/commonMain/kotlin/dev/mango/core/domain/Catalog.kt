package dev.mango.core.domain

/** An installed source's identity; [version] is the registry version at install, "" if none. */
data class SourceInfo(val sourceId: String, val name: String, val version: String = "")

/** Installed-source registry plus read path; install records identity and checksum only, the caller places the bundle file. */
interface CatalogRepository {
    suspend fun installedSources(): List<SourceInfo>
    suspend fun install(info: SourceInfo, bundleSha256: String)
    suspend fun search(sourceId: String, query: String, page: Int = 1): List<MangaEntry>
    suspend fun homeSections(sourceId: String): List<HomeSection>
    suspend fun details(sourceId: String, mangaId: String): MangaDetails
    suspend fun chapters(sourceId: String, mangaId: String): List<Chapter>
    suspend fun pages(sourceId: String, mangaId: String, chapterId: String): List<Page>

    /** [requests] with the source's request preparation applied; default identity. */
    suspend fun prepareImageRequests(sourceId: String, requests: List<SourceImageRequest>): List<SourceImageRequest> =
        requests

    /** Pins the UA for [sourceId]'s requests (cf_clearance is UA-bound) and evicts its cached engine. */
    suspend fun setUserAgent(sourceId: String, userAgent: String)

    /** Removes the source's row, bundle file and cached engine; library entries, progress and downloads are kept. */
    suspend fun uninstall(sourceId: String)
}
