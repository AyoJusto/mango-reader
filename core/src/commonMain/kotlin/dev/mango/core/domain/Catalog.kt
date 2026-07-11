package dev.mango.core.domain

/**
 * An installed source's identity, as tracked by the catalog (not the source's own JS state).
 * [version] is the registry version pinned at install time (M4.2); "" for sources installed
 * before that (manual bundle seeding, or a row untouched by a migration default).
 */
data class SourceInfo(val sourceId: String, val name: String, val version: String = "")

/**
 * Installed-source registry plus the read path, fanned out to whichever [MangaSource] backs
 * each sourceId. No infrastructure here (no DB, no engine types, no nio) — that lives in the
 * jvmMain implementation, which resolves a sourceId to a verified bundle and a [MangaSource].
 *
 * pages takes mangaId too: same 0.9 contract precedent as [MangaSource.getPages] (see M1.5).
 *
 * install only records the source's identity and pinned checksum; placing the bundle file
 * on disk is the caller's job in M2. M4's installer owns the actual download.
 */
interface CatalogRepository {
    suspend fun installedSources(): List<SourceInfo>
    suspend fun install(info: SourceInfo, bundleSha256: String)
    suspend fun search(sourceId: String, query: String, page: Int = 1): List<MangaEntry>
    suspend fun details(sourceId: String, mangaId: String): MangaDetails
    suspend fun chapters(sourceId: String, mangaId: String): List<Chapter>
    suspend fun pages(sourceId: String, mangaId: String, chapterId: String): List<Page>
}
