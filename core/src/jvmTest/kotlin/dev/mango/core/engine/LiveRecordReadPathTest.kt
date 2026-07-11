package dev.mango.core.engine

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Hits the real FlameComics site once and records every response the read path
 * (details, chapters, pages) makes into the fixtures dir. Excluded by default; run with:
 * gradlew :core:jvmTest -Plive --tests *LiveRecordReadPath*
 */
@Tag("live")
class LiveRecordReadPathTest {
    @Test
    fun recordReadPathFixtures() = runBlocking {
        val extension = PaperbackExtension("FlameComics", flameComicsBundle, RecordedHttp.recordingHost())

        val details = extension.getDetails("57")
        println("live details: ${details.entry.title} / authors=${details.authors} / status=${details.status}")

        val chapters = extension.getChapters("57")
        println("live chapters: ${chapters.size}; first: ${chapters.firstOrNull()}")
        assertTrue(chapters.isNotEmpty())

        val pages = extension.getPages("57", chapters.first().chapterId)
        println("live pages: ${pages.size}; first: ${pages.firstOrNull()}")
        assertTrue(pages.isNotEmpty())
    }
}
