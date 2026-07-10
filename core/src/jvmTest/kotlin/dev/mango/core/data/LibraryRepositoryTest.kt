package dev.mango.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.mango.core.db.MangoDatabase
import dev.mango.core.domain.LibraryRepository
import dev.mango.core.domain.MangaEntry
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/** Integration tests for [SqlLibraryRepository], driven only through the [LibraryRepository] contract. */
class LibraryRepositoryTest {
    private fun newRepository(): LibraryRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        MangoDatabase.Schema.create(driver)
        return SqlLibraryRepository(MangoDatabase(driver))
    }

    @Test
    fun emptyLibraryObservesAsEmptyList() = runTest {
        val repo = newRepository()
        assertEquals(emptyList(), repo.observeLibrary().first())
    }

    @Test
    fun addingAnItemEmitsItAndReAddingTheSameKeyStaysOneItem() = runTest {
        val repo = newRepository()
        val entry = MangaEntry(sourceId = "MangaBat", mangaId = "m1", title = "Solo Leveling", cover = "https://x/cover.jpg")

        repo.addToLibrary(entry)
        val afterFirstAdd = repo.observeLibrary().first()
        assertEquals(1, afterFirstAdd.size)
        assertEquals("Solo Leveling", afterFirstAdd[0].entry.title)
        assertEquals("https://x/cover.jpg", afterFirstAdd[0].entry.cover)

        repo.addToLibrary(entry.copy(title = "Solo Leveling (renamed)"))
        val afterReAdd = repo.observeLibrary().first()
        assertEquals(1, afterReAdd.size)
        assertEquals("Solo Leveling (renamed)", afterReAdd[0].entry.title)
    }

    @Test
    fun removingAnItemDropsItFromTheFlow() = runTest {
        val repo = newRepository()
        val entry = MangaEntry(sourceId = "MangaBat", mangaId = "m1", title = "Solo Leveling")
        repo.addToLibrary(entry)

        repo.removeFromLibrary("MangaBat", "m1")

        assertEquals(emptyList(), repo.observeLibrary().first())
    }

    @Test
    fun setProgressThenProgressRoundTrips() = runTest {
        val repo = newRepository()

        repo.setProgress("MangaBat", "m1", "c1", page = 3)
        val progress = repo.progress("MangaBat", "m1", "c1")

        assertEquals("c1", progress?.chapterId)
        assertEquals(3, progress?.page)
    }

    @Test
    fun progressForAnUnknownChapterIsNull() = runTest {
        val repo = newRepository()
        assertNull(repo.progress("MangaBat", "m1", "no-such-chapter"))
    }

    @Test
    fun settingProgressTwiceKeepsTheLatestPage() = runTest {
        val repo = newRepository()

        repo.setProgress("MangaBat", "m1", "c1", page = 3)
        repo.setProgress("MangaBat", "m1", "c1", page = 7)

        assertEquals(7, repo.progress("MangaBat", "m1", "c1")?.page)
    }

    @Test
    fun sameChapterIdUnderTwoSourcesStaysIsolated() = runTest {
        val repo = newRepository()

        repo.setProgress("MangaBat", "m1", "c1", page = 3)
        repo.setProgress("Toonily", "m1", "c1", page = 9)

        assertEquals(3, repo.progress("MangaBat", "m1", "c1")?.page)
        assertEquals(9, repo.progress("Toonily", "m1", "c1")?.page)
    }
}
