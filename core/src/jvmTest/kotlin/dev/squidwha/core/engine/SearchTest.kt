package dev.squidwha.core.engine

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

class SearchTest {
    @Test
    fun searchReturnsItemsFromRecordedFixtures() = runBlocking {
        val host = ApplicationHost(http = RecordedHttp.replayClient())
        val items = runFlameComicsSearch(host, title = "")["items"]!!.jsonArray
        assertTrue(items.isNotEmpty(), "expected search items from recorded fixtures")
        val first = items.first().jsonObject
        assertTrue(first["mangaId"]!!.jsonPrimitive.isString, "item has no mangaId: $first")
        assertTrue(first["title"]!!.jsonPrimitive.isString, "item has no title: $first")
    }

    @Test
    fun titleFilterNarrowsResults() = runBlocking {
        val all = runFlameComicsSearch(
            ApplicationHost(http = RecordedHttp.replayClient()), title = ""
        )["items"]!!.jsonArray.size
        val filtered = runFlameComicsSearch(
            ApplicationHost(http = RecordedHttp.replayClient()), title = "the"
        )["items"]!!.jsonArray.size
        assertTrue(filtered < all, "filtered=$filtered should be < all=$all")
    }
}
