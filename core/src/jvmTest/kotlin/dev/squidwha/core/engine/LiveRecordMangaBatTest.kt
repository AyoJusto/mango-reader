package dev.squidwha.core.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Paths
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
        val fixturesDir = Paths.get("src", "jvmTest", "resources", "fixtures")
        val host = ApplicationHost(
            http = HttpClient(CIO),
            onResponse = { url, status, body ->
                check(status == 200) { "live request to $url returned $status" }
                val file = fixturesDir.resolve(RecordedHttp.fixtureName(url))
                Files.createDirectories(file.parent)
                Files.write(file, body)
                println("recorded $url -> ${file.fileName} (${body.size} bytes)")
            },
        )
        val extension = PaperbackExtension("MangaBat", mangaBatBundle, host)

        val results = extension.search("solo")
        println("live search returned ${results.size} items; first: ${results.firstOrNull()}")
        assertTrue(results.isNotEmpty())
        val first = results.first()

        val details = extension.getDetails(first.mangaId)
        println("live details: ${details.entry.title} / authors=${details.authors} / status=${details.status}")

        val chapters = extension.getChapters(first.mangaId)
        println("live chapters: ${chapters.size}; first: ${chapters.firstOrNull()}")
        assertTrue(chapters.isNotEmpty())

        val pages = extension.getPages(chapters.first().chapterId, first.mangaId)
        println("live pages: ${pages.size}; first: ${pages.firstOrNull()}")
        assertTrue(pages.isNotEmpty())
    }
}
