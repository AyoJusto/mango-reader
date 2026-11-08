package dev.mango.core.data

import dev.mango.core.db.MangoDatabase
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.HomeSection
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.MangaSource
import dev.mango.core.domain.Page
import dev.mango.core.domain.SourceImageRequest
import dev.mango.core.domain.SourceInfo
import dev.mango.core.engine.BundleLoader
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UnknownSourceException(sourceId: String) : Exception("unknown source: $sourceId")

class MissingBundleException(sourceId: String, cause: Throwable) :
    Exception("no bundle file for installed source: $sourceId", cause)

// sourceId is extension-influenced: it must never name a path outside bundleDir; InkdexRepo enforces it too.
internal val SAFE_SOURCE_ID = Regex("[A-Za-z0-9_.-]+")

internal fun requireSafeSourceId(sourceId: String) {
    // all-dots ids ("..") pass the charset but become URL dot-segments in the installer
    require(sourceId.matches(SAFE_SOURCE_ID) && sourceId.any { it != '.' }) {
        "unsafe sourceId: $sourceId"
    }
}

/** Resolves sources by verifying the bundle against its pinned checksum once per source, then caching. */
class PaperbackCatalogRepository(
    private val db: MangoDatabase,
    private val bundleDir: Path,
    private val sourceFactory: (sourceId: String, bundleJs: String, userAgent: String?) -> MangaSource,
    private val context: CoroutineContext = Dispatchers.Default,
    private val clock: Clock = Clock.System,
) : CatalogRepository {
    private val resolved = ConcurrentHashMap<String, MangaSource>()

    override suspend fun installedSources(): List<SourceInfo> = withContext(context) {
        db.sourcesQueries.selectAllInstalledSources().executeAsList().map { row ->
            SourceInfo(sourceId = row.source_id, name = row.name, version = row.version)
        }
    }

    override suspend fun install(info: SourceInfo, bundleSha256: String) = withContext(context) {
        requireSafeSourceId(info.sourceId)
        db.sourcesQueries.upsertInstalledSource(
            source_id = info.sourceId,
            name = info.name,
            bundle_sha256 = bundleSha256,
            installed_at = clock.now().toEpochMilliseconds(),
            version = info.version,
        )
        // user_agent survives reinstall: the upsert leaves it out of the SET list (see sources.sq).
        // re-pinning a hash must not keep serving a source built from the old bundle
        resolved.remove(info.sourceId)
        Unit
    }

    override suspend fun search(sourceId: String, query: String, page: Int): List<MangaEntry> =
        resolveSource(sourceId).search(query, page)

    override suspend fun homeSections(sourceId: String): List<HomeSection> =
        resolveSource(sourceId).getHomeSections()

    override suspend fun details(sourceId: String, mangaId: String): MangaDetails =
        resolveSource(sourceId).getDetails(mangaId)

    override suspend fun chapters(sourceId: String, mangaId: String): List<Chapter> =
        resolveSource(sourceId).getChapters(mangaId)

    override suspend fun pages(sourceId: String, mangaId: String, chapterId: String): List<Page> =
        resolveSource(sourceId).getPages(mangaId, chapterId)

    override suspend fun prepareImageRequests(
        sourceId: String,
        requests: List<SourceImageRequest>,
    ): List<SourceImageRequest> = resolveSource(sourceId).prepareImageRequests(requests)

    override suspend fun setUserAgent(sourceId: String, userAgent: String) = withContext(context) {
        requireSafeSourceId(sourceId)
        db.sourcesQueries.updateUserAgent(user_agent = userAgent, source_id = sourceId)
        // cf_clearance is bound to its UA; a stale cached source would send the old UA and void the cookie.
        resolved.remove(sourceId)
        Unit
    }

    override suspend fun uninstall(sourceId: String) = withContext(context) {
        requireSafeSourceId(sourceId)
        db.sourcesQueries.deleteInstalledSource(source_id = sourceId)
        // Evict before the file delete: the delete can throw (Windows lock) and a removed source must stop executing.
        resolved.remove(sourceId)
        Files.deleteIfExists(bundleDir.resolve("$sourceId.index.js"))
        Unit
    }

    private suspend fun resolveSource(sourceId: String): MangaSource {
        requireSafeSourceId(sourceId)
        resolved[sourceId]?.let { return it }
        // Blocking file read and bundle evaluation must never run on a UI thread.
        return withContext(context) {
            // compute's bin lock keeps setUserAgent's evict from interleaving and a stale-UA source winning the cache.
            resolved.compute(sourceId) { _, existing ->
                existing ?: run {
                    val row = db.sourcesQueries.selectInstalledSource(sourceId).executeAsOneOrNull()
                        ?: throw UnknownSourceException(sourceId)
                    val bytes = try {
                        Files.readAllBytes(bundleDir.resolve("$sourceId.index.js"))
                    } catch (e: NoSuchFileException) {
                        throw MissingBundleException(sourceId, e)
                    }
                    val bundleJs = BundleLoader.verify(bytes, row.bundle_sha256)
                    sourceFactory(sourceId, bundleJs, row.user_agent)
                }
            }!!
        }
    }
}
