package dev.mango.core.engine

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Hits each real site once and records every response the discover flow makes into the
 * fixtures dir. Excluded by default; run with:
 * gradlew :core:jvmTest -Plive --tests *LiveRecordDiscover*
 *
 * Recording is keyed by URL, and the FlameComics homepage fixture is shared with the
 * search/read-path replay suites: when FlameComics rotates its Next.js buildId, re-record
 * ALL FlameComics LiveRecord tests together, never discover alone.
 *
 * A Cloudflare-challenged source is deliberately absent: 403 on the directory-resolution
 * request, and it has no recorded fixtures anywhere in the suite; its challenge coverage
 * lives in [ChallengeDetectionTest].
 */
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
