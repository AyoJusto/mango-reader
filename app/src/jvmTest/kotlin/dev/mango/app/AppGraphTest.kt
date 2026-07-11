package dev.mango.app

import dev.mango.core.domain.MangaEntry
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class AppGraphTest {
    @Test
    fun constructingCreatesTheDbFileAndDataDirs() {
        val dataDir = Files.createTempDirectory("app-graph-test")

        AppGraph(dataDir)

        assertTrue(Files.exists(dataDir.resolve("mango.db")), "expected mango.db to be created")
        assertTrue(Files.isDirectory(dataDir.resolve("bundles")), "expected bundles/ to be created")
        assertTrue(Files.isDirectory(dataDir.resolve("downloads")), "expected downloads/ to be created")
    }

    @Test
    fun libraryRepositoryRoundTrips() = runBlocking {
        val dataDir = Files.createTempDirectory("app-graph-test")
        val graph = AppGraph(dataDir)
        val entry = MangaEntry(sourceId = "Test", mangaId = "manga-1", title = "Test Manga")

        graph.library.addToLibrary(entry)
        val items = graph.library.observeLibrary().first()

        assertEquals(1, items.size)
        assertEquals(entry, items.single().entry)
    }

    @Test
    fun secondGraphOnSameDataDirSkipsSchemaCreateAndStillReads() = runBlocking {
        val dataDir = Files.createTempDirectory("app-graph-test")
        val first = AppGraph(dataDir)
        val entry = MangaEntry(sourceId = "Test", mangaId = "manga-1", title = "Test Manga")
        first.library.addToLibrary(entry)

        // constructing a second graph over the same dataDir must not fail (schema-create
        // is skipped because mango.db already exists) and must still see the earlier row
        val second = AppGraph(dataDir)
        val items = second.library.observeLibrary().first()

        assertEquals(1, items.size)
        assertEquals(entry, items.single().entry)
    }
}
