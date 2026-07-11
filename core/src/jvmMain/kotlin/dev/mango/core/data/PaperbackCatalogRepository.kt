package dev.mango.core.data

import dev.mango.core.db.MangoDatabase
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.MangaSource
import dev.mango.core.domain.Page
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

/** A catalog call named a sourceId with no matching installed_source row. */
class UnknownSourceException(sourceId: String) : Exception("unknown source: $sourceId")

/**
 * A source row exists but its bundle file is not in bundleDir — the normal state between
 * install (DB row only) and whatever places the file (M4's installer, or the caller in M2).
 */
class MissingBundleException(sourceId: String, cause: Throwable) :
    Exception("no bundle file for installed source: $sourceId", cause)

// sourceId becomes extension-influenced once M4 installs from bundle metadata; it must
// never be able to name a path outside bundleDir (no separators, no drive letters). Hoisted
// to file scope (M4.2) so InkdexRepo's installer can enforce the exact same rule before it
// ever touches the network, not just before this repository writes/reads the bundle file.
internal val SAFE_SOURCE_ID = Regex("[A-Za-z0-9_.-]+")

internal fun requireSafeSourceId(sourceId: String) {
    // all-dots ids ("..") pass the charset but become URL dot-segments in the installer
    require(sourceId.matches(SAFE_SOURCE_ID) && sourceId.any { it != '.' }) {
        "unsafe sourceId: $sourceId"
    }
}

/**
 * SQLDelight- and filesystem-backed [CatalogRepository]. Resolving a sourceId means: look up
 * its DB row, read `<bundleDir>/<sourceId>.index.js`, run it through [BundleLoader.verify]
 * against the pinned checksum, then hand the verified source to [sourceFactory]. That whole
 * chain happens once per source per repository instance — the resolved [MangaSource] is
 * cached, so a hot source never gets re-verified.
 */
class PaperbackCatalogRepository(
    private val db: MangoDatabase,
    private val bundleDir: Path,
    private val sourceFactory: (sourceId: String, bundleJs: String) -> MangaSource,
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
        // user_agent survives reinstall: the upsert is ON CONFLICT DO UPDATE with user_agent
        // deliberately absent from the SET list (see sources.sq) — M4.3 pins a UA there.
        // re-pinning a hash must not keep serving a source built from the old bundle
        resolved.remove(info.sourceId)
        Unit
    }

    override suspend fun search(sourceId: String, query: String, page: Int): List<MangaEntry> =
        resolveSource(sourceId).search(query, page)

    override suspend fun details(sourceId: String, mangaId: String): MangaDetails =
        resolveSource(sourceId).getDetails(mangaId)

    override suspend fun chapters(sourceId: String, mangaId: String): List<Chapter> =
        resolveSource(sourceId).getChapters(mangaId)

    override suspend fun pages(sourceId: String, mangaId: String, chapterId: String): List<Page> =
        resolveSource(sourceId).getPages(mangaId, chapterId)

    private suspend fun resolveSource(sourceId: String): MangaSource {
        requireSafeSourceId(sourceId)
        resolved[sourceId]?.let { return it }
        // the whole chain is dispatched: blocking file read and bundle evaluation
        // (sourceFactory boots the JS engine) must never run on a UI thread
        return withContext(context) {
            val row = db.sourcesQueries.selectInstalledSource(sourceId).executeAsOneOrNull()
                ?: throw UnknownSourceException(sourceId)
            val bytes = try {
                Files.readAllBytes(bundleDir.resolve("$sourceId.index.js"))
            } catch (e: NoSuchFileException) {
                throw MissingBundleException(sourceId, e)
            }
            val bundleJs = BundleLoader.verify(bytes, row.bundle_sha256)
            val source = sourceFactory(sourceId, bundleJs)
            // putIfAbsent so a race lands on the same instance rather than duplicating work.
            resolved.putIfAbsent(sourceId, source) ?: source
        }
    }
}
