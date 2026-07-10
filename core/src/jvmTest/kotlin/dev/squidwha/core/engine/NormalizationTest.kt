package dev.squidwha.core.engine

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NormalizationTest {
    @Test
    fun searchNormalizesToMangaEntries() = runBlocking {
        val extension = PaperbackExtension(
            sourceId = "FlameComics",
            bundleJs = flameComicsBundle,
            host = ApplicationHost(http = RecordedHttp.replayClient()),
        )
        val entries = extension.search(title = "")
        assertTrue(entries.isNotEmpty(), "expected entries from recorded fixtures")
        for (entry in entries) {
            assertEquals("FlameComics", entry.sourceId)
            assertTrue(entry.mangaId.isNotBlank(), "blank mangaId in $entry")
            assertTrue(entry.title.isNotBlank(), "blank title in $entry")
        }
        assertTrue(entries.any { it.cover != null }, "expected at least one cover url")
    }
}
