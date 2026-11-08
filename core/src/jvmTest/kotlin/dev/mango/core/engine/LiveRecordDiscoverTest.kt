package dev.mango.core.engine

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/** Live recording; run with: gradlew :core:jvmTest -Plive --tests *LiveRecordDiscover* */
@Tag("live")
class LiveRecordDiscoverTest {
    private fun recordDiscover(sourceId: String, bundleJs: String) = runBlocking {
        val sections = PaperbackExtension(sourceId, bundleJs, RecordedHttp.recordingHost()).getHomeSections()
        println("live discover [$sourceId] returned ${sections.size} sections: ${sections.map { it.id }}")
        assertTrue(sections.isNotEmpty(), "[$sourceId] expected at least one home section live")
    }

    @Test
    fun recordFlameComicsDiscoverFixtures() = recordDiscover("FlameComics", flameComicsBundle)

    @Test
    fun recordMangaBatDiscoverFixtures() = recordDiscover("MangaBat", mangaBatBundle)
}
