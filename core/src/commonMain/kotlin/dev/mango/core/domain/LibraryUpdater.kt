package dev.mango.core.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/** Aggregate result of one [LibraryUpdater.checkAll] pass across the whole library. */
data class UpdateCheckSummary(val seriesChecked: Int, val seriesFailed: Int, val newChapters: Int)

/**
 * Refreshes every library series against its source: pulls the live chapter list, writes it
 * through the catalog cache, updates the library's chapter count, and tallies chapters that
 * weren't in the cache before. Series belonging to different sources are checked in parallel;
 * series within the same source run strictly sequentially, so one source never sees concurrent
 * requests for different manga.
 */
class LibraryUpdater(
    private val catalog: CatalogRepository,
    private val cache: CatalogCache,
    private val library: LibraryRepository,
) {
    suspend fun checkAll(): UpdateCheckSummary = coroutineScope {
        val bySource = library.observeLibrary().first().groupBy { it.entry.sourceId }

        val results = bySource.values
            .map { seriesInSource -> async { seriesInSource.map { checkOne(it.entry.sourceId, it.entry.mangaId) } } }
            .awaitAll()
            .flatten()

        UpdateCheckSummary(
            seriesChecked = results.count { it.succeeded },
            seriesFailed = results.count { !it.succeeded },
            newChapters = results.sumOf { it.newChapters },
        )
    }

    private data class SeriesCheckResult(val succeeded: Boolean, val newChapters: Int)

    private suspend fun checkOne(sourceId: String, mangaId: String): SeriesCheckResult = try {
        val cached = cache.get(sourceId, mangaId)
        val chapters = catalog.chapters(sourceId, mangaId)
        val details = cached?.details ?: catalog.details(sourceId, mangaId)

        val newChapters = if (cached == null) {
            0
        } else {
            val previousIds = cached.chapters.map { it.chapterId }.toSet()
            chapters.count { it.chapterId !in previousIds }
        }

        cache.put(sourceId, mangaId, details, chapters)
        library.setChapterCount(sourceId, mangaId, chapters.size)

        SeriesCheckResult(succeeded = true, newChapters = newChapters)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        SeriesCheckResult(succeeded = false, newChapters = 0)
    }
}
