package dev.mango.app

import dev.mango.core.domain.SourceInfo
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag

/**
 * Milestone-exit smoke (M3.4): the PRODUCTION composition — AppGraph on the real
 * %APPDATA%/mango, real bundle file, live network — drives the full read path and leaves
 * one real series in the library so the launched app has something to show.
 *
 * Deliberately mutates real app data; tagged "live" so it never runs in a normal test pass.
 * Run: .\gradlew.bat :app:jvmTest -Plive --tests *LiveSmokeTest*
 */
@Tag("live")
class LiveSmokeTest {
    @Test
    fun liveReadPathThroughProductionComposition() {
        runBlocking {
            val graph = AppGraph()
            graph.catalog.install(
                SourceInfo(sourceId = "FlameComics", name = "Flame Comics"),
                bundleSha256 = "7bc0747ee748f812b9b42d585b83e6da0f9c45c6467a0044b22ad77ae144629a",
            )

            val results = graph.catalog.search("FlameComics", "")
            assertTrue(results.isNotEmpty(), "live search returned nothing")
            val entry = results.first()
            println("SMOKE search: ${results.size} results, first=${entry.title} cover=${entry.cover != null}")

            val details = graph.catalog.details("FlameComics", entry.mangaId)
            val chapters = graph.catalog.chapters("FlameComics", entry.mangaId)
            assertTrue(chapters.isNotEmpty(), "live chapters returned nothing")
            val pages = graph.catalog.pages("FlameComics", entry.mangaId, chapters.first().chapterId)
            assertTrue(pages.isNotEmpty(), "live pages returned nothing")
            println("SMOKE read path: '${details.entry.title}' ${chapters.size} chapters, first has ${pages.size} pages")

            graph.library.addToLibrary(entry)
            println("SMOKE library seeded with ${entry.title}")
        }
    }
}
