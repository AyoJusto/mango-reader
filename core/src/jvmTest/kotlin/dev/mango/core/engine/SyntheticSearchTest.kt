package dev.mango.core.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Search against the first-party Synthetic bundle, replayed from inline HTML served by a
 * fake host: gives the engine's HTML-parse path offline coverage fully under our control.
 */
class SyntheticSearchTest {
    @Test
    fun searchParsesEntriesFromInlineHtml() = runBlocking {
        val fakeSite = HttpClient(MockEngine {
            respond(
                """<manga id="m1" title="Solo Leveling" cover="https://synthetic.example/m1.jpg"/><manga id="m2" title="Omniscient Reader"/>""".encodeToByteArray(),
                HttpStatusCode.OK,
            )
        })
        val extension = PaperbackExtension("Synthetic", syntheticBundle, ApplicationHost(http = fakeSite))
        val entries = extension.search("solo")

        assertEquals(2, entries.size)
        assertEquals("m1", entries[0].mangaId)
        assertEquals("Solo Leveling", entries[0].title)
        assertEquals("https://synthetic.example/m1.jpg", entries[0].cover)
        assertEquals("m2", entries[1].mangaId)
        assertEquals("Omniscient Reader", entries[1].title)
        assertEquals(null, entries[1].cover)
    }
}
