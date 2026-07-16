package dev.mango.app

import dev.mango.core.domain.SourceInfo
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag

/**
 * Live smoke test: the PRODUCTION composition — AppGraph on the real %APPDATA%/mango, real
 * bundle file, live network — drives the full read path and leaves one real series in the
 * library so the launched app has something to show.
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

            // One page image fetched with the same policy headers production image fetches
            // send; a source that rejects them here rejects them in the reader and downloads.
            val page = pages.first()
            val policyHeaders = graph.headerPolicy.headersFor("FlameComics", page.url, page.headers)
            val request = HttpRequest.newBuilder(URI(page.url)).apply {
                policyHeaders.forEach { (name, value) -> header(name, value) }
            }.build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray())
            assertTrue(response.statusCode() in 200..299, "policy-headered image GET failed: ${response.statusCode()}")
            assertTrue(response.body().isNotEmpty(), "policy-headered image GET returned no bytes")
            println("SMOKE image: ${response.statusCode()}, ${response.body().size} bytes with policy headers")

            graph.library.addToLibrary(entry)
            println("SMOKE library seeded with ${entry.title}")
        }
    }
}
