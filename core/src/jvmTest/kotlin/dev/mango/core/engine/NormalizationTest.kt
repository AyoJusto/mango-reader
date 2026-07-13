package dev.mango.core.engine

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NormalizationTest {
    @Test
    fun searchNormalizesToMangaEntries() = runBlocking {
        val extension = PaperbackExtension(
            sourceId = "FlameComics",
            bundleJs = flameComicsBundle,
            host = RecordedHttp.replayHost(),
        )
        val entries = extension.search(query = "")
        assertTrue(entries.isNotEmpty(), "expected entries from recorded fixtures")
        for (entry in entries) {
            assertEquals("FlameComics", entry.sourceId)
            assertTrue(entry.mangaId.isNotBlank(), "blank mangaId in $entry")
            assertTrue(entry.title.isNotBlank(), "blank title in $entry")
        }
        assertTrue(entries.any { it.cover != null }, "expected at least one cover url")
    }

    @Test
    fun jsonNullAndMissingFieldsBecomeNamedErrorsNotNullStrings() {
        val withNullTitle = buildJsonObject {
            put("mangaId", JsonPrimitive("42"))
            put("title", JsonNull)
        }
        assertFailsWith<ExtensionDataException> { withNullTitle.requiredString("title", "X") }
        assertFailsWith<ExtensionDataException> { withNullTitle.requiredString("missing", "X") }
        assertEquals("42", withNullTitle.requiredString("mangaId", "X"))
    }
}
