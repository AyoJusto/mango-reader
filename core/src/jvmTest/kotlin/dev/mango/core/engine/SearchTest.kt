package dev.mango.core.engine

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

class SearchTest {
    @Test
    fun searchReturnsItemsFromRecordedFixtures() = runBlocking {
        val host = RecordedHttp.replayHost()
        val items = runFlameComicsSearch(host, title = "")["items"]!!.jsonArray
        assertTrue(items.isNotEmpty(), "expected search items from recorded fixtures")
        val first = items.first().jsonObject
        assertTrue(first["mangaId"]!!.jsonPrimitive.isString, "item has no mangaId: $first")
        assertTrue(first["title"]!!.jsonPrimitive.isString, "item has no title: $first")
    }

    @Test
    fun titleFilterNarrowsResults() = runBlocking {
        val all = runFlameComicsSearch(
            RecordedHttp.replayHost(), title = ""
        )["items"]!!.jsonArray.size
        val filtered = runFlameComicsSearch(
            RecordedHttp.replayHost(), title = "the"
        )["items"]!!.jsonArray.size
        assertTrue(filtered < all, "filtered=$filtered should be < all=$all")
    }
}
