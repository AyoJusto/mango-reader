package dev.mango.core.engine

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Hits the real MangaBat site once and records every response the read path
 * (search, details, chapters, pages) makes into the fixtures dir. Excluded by default;
 * run with: gradlew :core:jvmTest -Plive --tests *LiveRecordMangaBat*
 */
@Tag("live")
class LiveRecordMangaBatTest {
    @Test
    fun recordReadPathFixtures() = runBlocking {
        val extension = PaperbackExtension("MangaBat", mangaBatBundle, RecordedHttp.recordingHost())

        val results = extension.search("solo")
        println("live search returned ${results.size} items; first: ${results.firstOrNull()}")
        assertTrue(results.isNotEmpty())
        val first = results.first()

        val details = extension.getDetails(first.mangaId)
        println("live details: ${details.entry.title} / authors=${details.authors} / status=${details.status}")

        val chapters = extension.getChapters(first.mangaId)
        println("live chapters: ${chapters.size}; first: ${chapters.firstOrNull()}")
        assertTrue(chapters.isNotEmpty())

        val pages = extension.getPages(first.mangaId, chapters.first().chapterId)
        println("live pages: ${pages.size}; first: ${pages.firstOrNull()}")
        assertTrue(pages.isNotEmpty())
    }
}
