package dev.mango.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.mango.core.db.MangoDatabase
import dev.mango.core.domain.LibraryRepository
import dev.mango.core.domain.MangaEntry
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/** Integration tests for the collections half of [SqlLibraryRepository], driven only through [LibraryRepository]. */
class CollectionsTest {
    /** Tick-per-call clock: keeps added_at/updated_at writes distinct within a fast test. */
    private class TickingClock : Clock {
        private var millis = 0L
        override fun now(): Instant = Instant.fromEpochMilliseconds(++millis)
    }

    private fun newRepository(): LibraryRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        MangoDatabase.Schema.create(driver)
        return SqlLibraryRepository(MangoDatabase(driver), clock = TickingClock())
    }

    private val entry = MangaEntry(sourceId = "MangaBat", mangaId = "m1", title = "Solo Leveling")

    @Test
    fun firstAddOnAVirginDbSelfHealsADefaultReadingAndFilesTheSeries() = runTest {
        val repo = newRepository()

        repo.addToLibrary(entry)

        val reading = repo.observeCollections().first().single()
        assertEquals("Reading", reading.name)
        assertTrue(reading.isDefault)
        assertEquals(setOf(reading.id), repo.observeLibrary().first().single().collectionIds)
    }

    @Test
    fun createCollectionAppendsAtTheNextPositionAndRejectsDuplicateNames() = runTest {
        val repo = newRepository()
        repo.addToLibrary(entry)

        val id = repo.createCollection("Favorites")

        val collections = repo.observeCollections().first()
        assertEquals(listOf("Reading", "Favorites"), collections.map { it.name })
        assertEquals(id, collections.last().id)
        assertEquals(1, collections.last().position)
        assertFailsWith<IllegalArgumentException> { repo.createCollection("Favorites") }
    }

    @Test
    fun renameRejectsDuplicatesButAllowsRenamingToItsOwnCurrentName() = runTest {
        val repo = newRepository()
        repo.addToLibrary(entry)
        val favoritesId = repo.createCollection("Favorites")

        assertFailsWith<IllegalArgumentException> { repo.renameCollection(favoritesId, "Reading") }
        repo.renameCollection(favoritesId, "Favorites")
        repo.renameCollection(favoritesId, "Faves")

        assertEquals(listOf("Reading", "Faves"), repo.observeCollections().first().map { it.name })
    }

    @Test
    fun setMembershipReplacesWholesaleAndObserveLibraryReflectsIt() = runTest {
        val repo = newRepository()
        repo.addToLibrary(entry)
        val defaultId = repo.observeCollections().first().single().id
        val a = repo.createCollection("A")
        val b = repo.createCollection("B")

        repo.setMembership("MangaBat", "m1", setOf(a, b))
        assertEquals(setOf(a, b), repo.observeLibrary().first().single().collectionIds)

        repo.setMembership("MangaBat", "m1", setOf(defaultId))
        assertEquals(setOf(defaultId), repo.observeLibrary().first().single().collectionIds)
    }

    @Test
    fun reAddOfAnExistingSeriesDoesNotRefileItIntoTheDefault() = runTest {
        val repo = newRepository()
        repo.addToLibrary(entry)
        val defaultId = repo.observeCollections().first().single().id
        val shelf = repo.createCollection("Shelf")
        repo.setMembership("MangaBat", "m1", setOf(shelf))

        // the download path: addToLibrary fires again for a series already in the library
        repo.addToLibrary(entry)

        val ids = repo.observeLibrary().first().single().collectionIds
        assertEquals(setOf(shelf), ids)
        assertTrue(defaultId !in ids)
    }

    @Test
    fun deleteUnfilesMembersKeepsTheSeriesAndPromotesANewDefault() = runTest {
        val repo = newRepository()
        repo.addToLibrary(entry)
        val readingId = repo.observeCollections().first().single().id
        val shelfId = repo.createCollection("Shelf")
        repo.setMembership("MangaBat", "m1", setOf(readingId, shelfId))

        repo.deleteCollection(readingId)

        val remaining = repo.observeCollections().first().single()
        assertEquals(shelfId, remaining.id)
        assertTrue(remaining.isDefault)
        assertEquals(setOf(shelfId), repo.observeLibrary().first().single().collectionIds)
        assertFailsWith<IllegalStateException> { repo.deleteCollection(shelfId) }
    }

    @Test
    fun reorderCollectionsRewritesPositionsToListOrder() = runTest {
        val repo = newRepository()
        repo.addToLibrary(entry)
        val readingId = repo.observeCollections().first().single().id
        val a = repo.createCollection("A")
        val b = repo.createCollection("B")

        repo.reorderCollections(listOf(b, readingId, a))

        val ordered = repo.observeCollections().first()
        assertEquals(listOf(b, readingId, a), ordered.map { it.id })
        assertEquals(listOf(0, 1, 2), ordered.map { it.position })
    }

    @Test
    fun removeFromLibraryDeletesMembershipSoReAddFilesOnlyIntoTheDefault() = runTest {
        val repo = newRepository()
        repo.addToLibrary(entry)
        val defaultId = repo.observeCollections().first().single().id
        val shelf = repo.createCollection("Shelf")
        repo.setMembership("MangaBat", "m1", setOf(defaultId, shelf))

        repo.removeFromLibrary("MangaBat", "m1")
        repo.addToLibrary(entry)

        assertEquals(setOf(defaultId), repo.observeLibrary().first().single().collectionIds)
    }

    @Test
    fun membershipInTwoCollectionsDoesNotChangeUnreadOrNewCounts() = runTest {
        val repo = newRepository()
        repo.addToLibrary(entry)
        repo.setChapterCount("MangaBat", "m1", 10)
        repo.setProgress("MangaBat", "m1", "c1", page = 1)
        repo.setProgress("MangaBat", "m1", "c2", page = 1)
        val before = repo.observeLibrary().first().single()

        val defaultId = repo.observeCollections().first().single().id
        val shelf = repo.createCollection("Shelf")
        repo.setMembership("MangaBat", "m1", setOf(defaultId, shelf))

        val after = repo.observeLibrary().first().single()
        assertEquals(8, after.unreadCount)
        assertEquals(before.unreadCount, after.unreadCount)
        assertEquals(before.newCount, after.newCount)
    }
}
