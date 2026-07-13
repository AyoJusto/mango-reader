package dev.mango.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** Unit coverage for [recordSearch] and [removeSearch] — pure list transforms, no I/O. */
class SearchHistoryTest {
    @Test
    fun reRunningAQueryMovesItToTheTopWithTheNewTimestamp() {
        val history = listOf(
            SearchHistoryEntry("solo leveling", 100L),
            SearchHistoryEntry("tower of god", 200L),
        )

        val result = recordSearch(history, "solo leveling", 300L)

        assertEquals(
            listOf(SearchHistoryEntry("solo leveling", 300L), SearchHistoryEntry("tower of god", 200L)),
            result,
        )
    }

    @Test
    fun capsAtTenDroppingTheOldestEntry() {
        // newest-first order: index 0 is the most recent, index 9 ("query10") the oldest.
        val history = (1..10).map { SearchHistoryEntry("query$it", it.toLong()) }

        val result = recordSearch(history, "new query", 11L)

        assertEquals(10, result.size)
        assertEquals("new query", result.first().query)
        assertEquals(false, result.any { it.query == "query10" })
        assertEquals(true, result.any { it.query == "query1" })
    }

    @Test
    fun blankOrWhitespaceQueryIsANoOp() {
        val history = listOf(SearchHistoryEntry("solo leveling", 100L))

        assertEquals(history, recordSearch(history, "", 200L))
        assertEquals(history, recordSearch(history, "   ", 200L))
    }

    @Test
    fun recordSearchTrimsTheQueryForStorage() {
        val result = recordSearch(emptyList(), "  solo leveling  ", 100L)

        assertEquals(listOf(SearchHistoryEntry("solo leveling", 100L)), result)
    }

    @Test
    fun removeSearchRemovesExactlyTheMatchingQuery() {
        val history = listOf(
            SearchHistoryEntry("solo leveling", 100L),
            SearchHistoryEntry("tower of god", 200L),
        )

        val result = removeSearch(history, "solo leveling")

        assertEquals(listOf(SearchHistoryEntry("tower of god", 200L)), result)
    }
}
