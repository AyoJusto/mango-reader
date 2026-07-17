package dev.mango.core.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.mango.core.db.MangoDatabase
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.Download
import dev.mango.core.domain.DownloadManager
import dev.mango.core.domain.DownloadStatus
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.SourceHeaderPolicy
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.Url
import io.ktor.http.isSuccess
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.logging.Level
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * SQLDelight- and filesystem-backed [DownloadManager]. Fetches chapter pages straight to disk
 * under `<root>/<sourceId>/<mangaId>/<chapterId>/NNNN.ext`.
 *
 * [processQueue] drains QUEUED rows sequentially, oldest first: one chapter is fully fetched
 * (or fails) before the next is picked up. A failure marks that row FAILED and moves on — it
 * never aborts the rest of the queue — so calling processQueue again after a failure is always
 * safe and simply resumes from whatever is still QUEUED.
 */
class FileDownloadManager(
    private val db: MangoDatabase,
    private val catalog: CatalogRepository,
    private val http: HttpClient,
    private val root: Path,
    private val pageDelayMillis: Long = 500,
    private val context: CoroutineContext = Dispatchers.IO,
    private val clock: Clock = Clock.System,
    private val headerPolicy: SourceHeaderPolicy? = null,
) : DownloadManager {
    // ponytail: JUL because :core has no logging dependency; swap when the app picks one
    private val log = java.util.logging.Logger.getLogger(FileDownloadManager::class.java.name)

    // serializes concurrent processQueue callers: the second drains an empty queue
    private val queueLock = Mutex()

    override fun observeDownloads(): Flow<List<Download>> =
        db.downloadsQueries.selectAllDownloads().asFlow().mapToList(context).map { rows ->
            rows.map { row ->
                Download(
                    sourceId = row.source_id,
                    mangaId = row.manga_id,
                    chapterId = row.chapter_id,
                    mangaTitle = row.manga_title,
                    chapterNumber = row.chapter_number,
                    status = DownloadStatus.valueOf(row.status),
                    pagesTotal = row.pages_total.toInt(),
                    pagesDone = row.pages_done.toInt(),
                )
            }
        }

    override suspend fun enqueue(entry: MangaEntry, chapter: Chapter) = withContext(context) {
        // re-enqueueing an existing (e.g. FAILED) row resets it to a fresh QUEUED/0/0 —
        // resuming from the last completed page instead is a known upgrade path, not this one
        db.downloadsQueries.upsertDownload(
            source_id = entry.sourceId,
            manga_id = entry.mangaId,
            chapter_id = chapter.chapterId,
            manga_title = entry.title,
            chapter_number = chapter.number,
            status = DownloadStatus.QUEUED.name,
            pages_total = 0,
            pages_done = 0,
            updated_at = clock.now().toEpochMilliseconds(),
        )
        Unit
    }

    override suspend fun processQueue(): Unit = withContext(context) {
        queueLock.withLock {
            while (true) {
                // re-queried every iteration: the oldest QUEUED row after the previous one's
                // outcome (DONE or FAILED) has already been written
                val row = db.downloadsQueries.selectQueuedDownloads().executeAsList().firstOrNull() ?: break
                downloadChapter(row.source_id, row.manga_id, row.chapter_id)
            }
        }
    }

    private suspend fun downloadChapter(sourceId: String, mangaId: String, chapterId: String) {
        var pagesTotal = 0
        var pagesDone = 0
        markProgress(sourceId, mangaId, chapterId, DownloadStatus.RUNNING, pagesTotal, pagesDone)
        try {
            val pages = catalog.pages(sourceId, mangaId, chapterId)
            pagesTotal = pages.size
            markProgress(sourceId, mangaId, chapterId, DownloadStatus.RUNNING, pagesTotal, pagesDone)

            val chapterDir = root
                .resolve(safeSegment(sourceId))
                .resolve(safeSegment(mangaId))
                .resolve(safeSegment(chapterId))
            // Prepared once for the whole chapter (interceptor chain + jar/UA/casing), not
            // per page: a source whose signed URLs expire mid-chapter would break late pages —
            // accepted, not mitigated (Paperback's own pipeline has the same shape).
            val preparedPages = headerPolicy?.withPolicyHeaders(sourceId, pages) ?: pages
            // ponytail: pages within a chapter are fetched one at a time; parallel page
            // fetches are the upgrade if downloads feel slow.
            preparedPages.forEachIndexed { i, page ->
                val response = http.get(page.url) {
                    page.headers.forEach { (name, value) -> header(name, value) }
                }
                if (!response.status.isSuccess()) {
                    throw IOException("GET ${page.url} failed with status ${response.status}")
                }
                val bytes = response.body<ByteArray>()
                // loop index, not page.index: extension-controlled values must not pick names
                val file = chapterDir.resolve("%04d.%s".format(i, extensionFor(page.url)))
                Files.createDirectories(file.parent)
                Files.write(file, bytes)

                pagesDone = i + 1
                markProgress(sourceId, mangaId, chapterId, DownloadStatus.RUNNING, pagesTotal, pagesDone)
                delay(pageDelayMillis.milliseconds)
            }
            markProgress(sourceId, mangaId, chapterId, DownloadStatus.DONE, pagesTotal, pagesDone)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log(Level.WARNING, "download failed: $sourceId/$mangaId/$chapterId", e)
            markProgress(sourceId, mangaId, chapterId, DownloadStatus.FAILED, pagesTotal, pagesDone)
        }
    }

    // No dedicated "select one row by key" query exists (only selectAllDownloads and
    // selectQueuedDownloads), so this filters the small in-memory row list instead of
    // adding SQL.
    override suspend fun localPages(sourceId: String, mangaId: String, chapterId: String): List<String>? =
        withContext(context) {
            val row = db.downloadsQueries.selectAllDownloads().executeAsList().firstOrNull {
                it.source_id == sourceId && it.manga_id == mangaId && it.chapter_id == chapterId
            } ?: return@withContext null
            if (DownloadStatus.valueOf(row.status) != DownloadStatus.DONE) return@withContext null

            val chapterDir = root
                .resolve(safeSegment(sourceId))
                .resolve(safeSegment(mangaId))
                .resolve(safeSegment(chapterId))
            if (!Files.isDirectory(chapterDir)) return@withContext null

            // the "%04d." prefix on every filename makes lexicographic filename order the same
            // as page order
            val files = Files.list(chapterDir).use { stream ->
                stream.filter(Files::isRegularFile).sorted(compareBy { it.fileName.toString() }).toList()
            }
            if (files.size != row.pages_total.toInt()) return@withContext null

            files.map { it.toAbsolutePath().toString() }
        }

    // ponytail: no queueLock — clearing while a chapter is actively downloading may leave
    // orphaned files for that one in-flight chapter (its progress updates hit no row, and a
    // later clear removes the files); blocking the UI on a full queue drain isn't worth it.
    override suspend fun clearDownloads(sourceId: String, mangaId: String): Unit = withContext(context) {
        db.downloadsQueries.deleteDownloadsForManga(source_id = sourceId, manga_id = mangaId)

        // Best-effort file sweep: the rows are already gone (no phantom "downloaded" state), so
        // a locked file (Windows, mid-download) must not crash the caller's fire-and-forget
        // scope — leftovers are orphans the next clear removes.
        val mangaDir = root.resolve(safeSegment(sourceId)).resolve(safeSegment(mangaId))
        try {
            if (Files.isDirectory(mangaDir)) {
                Files.walk(mangaDir).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log(Level.WARNING, "clearDownloads left orphaned files under $mangaDir", e)
        }
    }

    private fun markProgress(
        sourceId: String,
        mangaId: String,
        chapterId: String,
        status: DownloadStatus,
        pagesTotal: Int,
        pagesDone: Int,
    ) {
        db.downloadsQueries.updateDownloadProgress(
            status = status.name,
            pages_total = pagesTotal.toLong(),
            pages_done = pagesDone.toLong(),
            updated_at = clock.now().toEpochMilliseconds(),
            source_id = sourceId,
            manga_id = mangaId,
            chapter_id = chapterId,
        )
    }

    // the URL's own path, not its query string, so a signed "?sig=..." URL for a .png
    // still resolves to "png" instead of leaking query params into a filename
    private fun extensionFor(url: String): String {
        val lastSegment = Url(url).encodedPath.substringAfterLast('/')
        val ext = lastSegment.substringAfterLast('.', "").lowercase()
        return if (ext.matches(SAFE_EXTENSION)) ext else "img"
    }

    /**
     * Extension-supplied ids (mangaId, chapterId — and sourceId, since this class can't rely
     * on its CatalogRepository having guarded it) must never name a path outside [root]:
     * Path.resolve escapes via `..` and DISCARDS the base entirely for absolute arguments.
     * Well-behaved ids pass through readable; anything else becomes slug + content hash
     * (deterministic, so re-enqueue lands in the same directory).
     */
    private fun safeSegment(id: String): String {
        if (id.matches(SAFE_SEGMENT) && id != "." && id != "..") return id
        val hash = MessageDigest.getInstance("SHA-256").digest(id.encodeToByteArray()).toHexString().take(12)
        val slug = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        return "${slug}_$hash"
    }

    private companion object {
        val SAFE_SEGMENT = Regex("[A-Za-z0-9._-]+")
        val SAFE_EXTENSION = Regex("[a-z0-9]{1,5}")
    }
}
