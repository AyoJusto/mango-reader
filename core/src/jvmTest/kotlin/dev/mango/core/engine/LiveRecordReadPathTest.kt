package dev.mango.core.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Paths
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
        val extension = PaperbackExtension("FlameComics", flameComicsBundle, host)

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
