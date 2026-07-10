package dev.squidwha.core.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Paths
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
        val items = runFlameComicsSearch(host, title = "")["items"]!!.jsonArray
        println("live search returned ${items.size} items; first: ${items.firstOrNull()}")
        assertTrue(items.isNotEmpty())
    }
}
