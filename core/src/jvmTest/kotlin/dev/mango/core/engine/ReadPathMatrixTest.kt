package dev.mango.core.engine

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replays the recorded read-path fixtures through [PaperbackExtension], per source.
 * MangaBat only: Toonily's one budgeted recording run hit a non-200 on its first request
 * (Cloudflare-protected Madara site), so there are no Toonily fixtures to replay — see
 * docs/compatibility.md.
 */
class ReadPathMatrixTest {
    private fun mangaBat() =
        PaperbackExtension("MangaBat", mangaBatBundle, ApplicationHost(http = RecordedHttp.replayClient()))

    @Test
    fun mangaBatSearchReturnsItems() = runBlocking {
        val entries = mangaBat().search("solo")
        assertTrue(entries.isNotEmpty(), "expected search entries from recorded fixtures")
        for (entry in entries) {
            assertTrue(entry.mangaId.isNotBlank(), "blank mangaId in $entry")
            assertTrue(entry.title.isNotBlank(), "blank title in $entry")
        }
    }

    @Test
    fun mangaBatDetailsNormalize() = runBlocking {
        val ext = mangaBat()
        val first = ext.search("solo").first()
        val details = ext.getDetails(first.mangaId)
        assertTrue(details.entry.title.isNotBlank(), "expected a non-blank title")
        // the recorded page lists no author ("Author(s):" is empty on the site), so an
        // empty list is the correctly parsed value, not a parse failure
        assertEquals(emptyList(), details.authors)
        assertTrue(details.tags.isNotEmpty(), "expected parsed tags: ${details.tags}")
    }

    @Test
    fun mangaBatChaptersAreNonEmptyWithNumbers() = runBlocking {
        val ext = mangaBat()
        val first = ext.search("solo").first()
        val chapters = ext.getChapters(first.mangaId)
        assertTrue(chapters.isNotEmpty(), "expected chapters from recorded fixtures")
        chapters.forEach { chapter ->
            assertTrue(chapter.number.isFinite(), "chapter has no parseable number: $chapter")
        }
    }

    @Test
    fun mangaBatPagesAreAbsoluteUrlsInIndexOrder() = runBlocking {
        val ext = mangaBat()
        val first = ext.search("solo").first()
        val firstChapter = ext.getChapters(first.mangaId).first()
        val pages = ext.getPages(first.mangaId, firstChapter.chapterId)
        assertTrue(pages.isNotEmpty(), "expected pages from recorded fixtures")
        pages.forEachIndexed { i, page ->
            assertEquals(i, page.index, "pages out of index order: $pages")
            assertTrue(
                page.url.startsWith("http://") || page.url.startsWith("https://"),
                "page url is not absolute: ${page.url}",
            )
        }
    }
}
