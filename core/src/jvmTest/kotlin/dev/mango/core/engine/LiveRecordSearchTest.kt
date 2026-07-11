package dev.mango.core.engine

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Hits the real FlameComics site once and records every response the search flow
 * makes into the fixtures dir. Excluded by default; run with:
 * gradlew :core:jvmTest -Plive --tests *LiveRecord*
 */
@Tag("live")
class LiveRecordSearchTest {
    @Test
    fun recordSearchFixtures() = runBlocking {
        val host = RecordedHttp.recordingHost()
        val items = runFlameComicsSearch(host, title = "")["items"]!!.jsonArray
        println("live search returned ${items.size} items; first: ${items.firstOrNull()}")
        assertTrue(items.isNotEmpty())
    }
}
