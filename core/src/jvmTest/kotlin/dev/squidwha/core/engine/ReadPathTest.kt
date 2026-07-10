package dev.squidwha.core.engine

import dev.squidwha.core.domain.MangaStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadPathTest {
    private fun extension() =
        PaperbackExtension(
            "FlameComics",
            flameComicsBundle,
            ApplicationHost(http = RecordedHttp.replayClient()),
        )

    @Test
    fun detailsNormalizeAllFields() = runBlocking {
        val details = extension().getDetails("57")
        assertEquals("57", details.entry.mangaId)
        assertEquals("IRL Quest", details.entry.title)
        assertEquals(listOf("Lee Joo-Woon"), details.authors)
        assertEquals(MangaStatus.ONGOING, details.status)
        assertTrue("Action" in details.tags, "expected Action in tags: ${details.tags}")
        assertTrue(!details.description.isNullOrBlank(), "expected a description")
    }

    @Test
    fun chaptersAreNonEmptyWithNumbers() = runBlocking {
        val chapters = extension().getChapters("57")
        assertTrue(chapters.isNotEmpty(), "expected chapters from recorded fixtures")
        chapters.forEach { chapter ->
            assertTrue(chapter.number.isFinite(), "chapter has no parseable number: $chapter")
        }
    }

    @Test
    fun pagesAreAbsoluteUrlsInIndexOrder() = runBlocking {
        val ext = extension()
        val firstChapter = ext.getChapters("57").first()
        val pages = ext.getPages(firstChapter.chapterId, "57")
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
