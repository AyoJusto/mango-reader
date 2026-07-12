package dev.mango.app

import dev.mango.core.domain.Page
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure unit tests for [pagesToPrefetch] — no Coil, no composition. */
class ReaderPrefetchTest {
    private fun page(index: Int) = Page(index = index, url = "https://example.test/$index.jpg")

    private val onlineSegment = ReaderSegment(chapterId = "c1", shortLabel = "Ch. 1", label = "Ch. 1", pages = emptyList())
    private val offlineSegment = ReaderSegment(
        chapterId = "c2",
        shortLabel = "Ch. 2",
        label = "Ch. 2",
        pages = emptyList(),
        offline = true,
    )
    private val onlineSegment2 = ReaderSegment(chapterId = "c3", shortLabel = "Ch. 3", label = "Ch. 3", pages = emptyList())

    private fun pageRow(index: Int, segmentIndex: Int = 0) = ReaderRow.PageRow(segmentIndex, page(index))

    @Test
    fun returnsUpToCountPagesAfterTheLastVisibleIndex() {
        val rows = (0..9).map { pageRow(it) }

        val result = pagesToPrefetch(rows, listOf(onlineSegment), lastVisibleIndex = 2, count = 5)

        assertEquals(listOf(3, 4, 5, 6, 7), result.map { it.index })
    }

    @Test
    fun neverReturnsRowsAtOrBeforeTheLastVisibleIndex() {
        val rows = (0..3).map { pageRow(it) }

        val result = pagesToPrefetch(rows, listOf(onlineSegment), lastVisibleIndex = 3, count = 5)

        assertEquals(emptyList(), result)
    }

    @Test
    fun respectsTheRequestedCountEvenWhenMoreRowsExist() {
        val rows = (0..99).map { pageRow(it) }

        val result = pagesToPrefetch(rows, listOf(onlineSegment), lastVisibleIndex = -1, count = 3)

        assertEquals(listOf(0, 1, 2), result.map { it.index })
    }

    @Test
    fun skipsPagesWhoseSegmentIsOfflineWithoutConsumingTheCount() {
        // Distinct page indices per segment so the result can be asserted directly, with no
        // need to trace pages back to a segment.
        val rows = listOf(
            pageRow(0, segmentIndex = 0), // online
            pageRow(0, segmentIndex = 1), // offline — must be skipped, not counted
            pageRow(1, segmentIndex = 1), // offline — must be skipped, not counted
            pageRow(9, segmentIndex = 2), // online
        )
        val segments = listOf(onlineSegment, offlineSegment, onlineSegment2)

        val result = pagesToPrefetch(rows, segments, lastVisibleIndex = -1, count = 2)

        // Both online pages come back despite two offline rows sitting between them — the
        // offline skips must not eat into the requested count.
        assertEquals(listOf(0, 9), result.map { it.index })
    }
}
