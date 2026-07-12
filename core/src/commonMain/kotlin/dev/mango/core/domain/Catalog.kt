package dev.mango.core.domain

/**
 * An installed source's identity, as tracked by the catalog (not the source's own JS state).
 * [version] is the registry version pinned at install time; "" for sources installed without
 * one (manual bundle seeding, or a row untouched by a migration default).
 */
data class SourceInfo(val sourceId: String, val name: String, val version: String = "")

/**
 * Installed-source registry plus the read path, fanned out to whichever [MangaSource] backs
 * each sourceId. No infrastructure here (no DB, no engine types, no nio) — that lives in the
 * jvmMain implementation, which resolves a sourceId to a verified bundle and a [MangaSource].
 *
 * pages takes mangaId too, matching [MangaSource.getPages]'s contract.
 *
 * install only records the source's identity and pinned checksum; placing the bundle file
 * on disk and downloading it are the caller's responsibility, not this method's.
 */
interface CatalogRepository {
    suspend fun installedSources(): List<SourceInfo>
    suspend fun install(info: SourceInfo, bundleSha256: String)
    suspend fun search(sourceId: String, query: String, page: Int = 1): List<MangaEntry>
    suspend fun homeSections(sourceId: String): List<HomeSection>
    suspend fun details(sourceId: String, mangaId: String): MangaDetails
    suspend fun chapters(sourceId: String, mangaId: String): List<Chapter>
    suspend fun pages(sourceId: String, mangaId: String, chapterId: String): List<Page>

    /** Pins the UA used for [sourceId]'s host requests (cf_clearance is UA-bound). Evicts the source's cached engine instance. */
    suspend fun setUserAgent(sourceId: String, userAgent: String)

    /**
     * Removes the installed source: its DB row, its bundle file on disk, and any cached engine
     * instance (the bundle stops being executable). The source's library entries, read progress,
     * and downloads are deliberately untouched — downloads stay readable offline; live catalog
     * calls for the source throw an unknown-source error until it is reinstalled.
     */
    suspend fun uninstall(sourceId: String)
}
