package dev.mango.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.mango.core.db.MangoDatabase
import dev.mango.core.domain.LibraryRepository
import dev.mango.core.domain.MangaEntry
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/** Integration tests for [SqlLibraryRepository], driven only through the [LibraryRepository] contract. */
class LibraryRepositoryTest {
    /**
     * A tick-per-call clock: back-to-back writes in a fast test can otherwise land in the same
     * real millisecond, making updated_at ties (and ordering by it) nondeterministic.
     */
    private class TickingClock : Clock {
        private var millis = 0L
        override fun now(): Instant = Instant.fromEpochMilliseconds(++millis)
    }

    private fun newRepository(clock: Clock = TickingClock()): LibraryRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        MangoDatabase.Schema.create(driver)
        return SqlLibraryRepository(MangoDatabase(driver), clock = clock)
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

    @Test
    fun finishedChapterIdsContainsOnlyFinishedChapters() = runTest {
        val repo = newRepository()

        repo.setProgress("MangaBat", "m1", "c1", page = 3, finished = true)
        repo.setProgress("MangaBat", "m1", "c2", page = 1)

        val finishedIds = repo.finishedChapterIds("MangaBat", "m1")
        assertTrue("c1" in finishedIds)
        assertTrue("c2" !in finishedIds)
    }

    @Test
    fun finishedStaysStickyAndPageStaysLiveOnceAChapterIsFinished() = runTest {
        val repo = newRepository()

        repo.setProgress("MangaBat", "m1", "c1", page = 5, finished = true)
        repo.setProgress("MangaBat", "m1", "c1", page = 0, finished = false)

        val progress = repo.progress("MangaBat", "m1", "c1")
        assertEquals(true, progress?.finished)
        assertEquals(0, progress?.page)
    }

    @Test
    fun latestProgressReturnsTheMostRecentlyWrittenRow() = runTest {
        val repo = newRepository()

        repo.setProgress("MangaBat", "m1", "c1", page = 1)
        repo.setProgress("MangaBat", "m1", "c2", page = 2)

        assertEquals("c2", repo.latestProgress("MangaBat", "m1")?.chapterId)
    }

    @Test
    fun setProgressThenLatestProgressRoundTripsTheChapterNumber() = runTest {
        val repo = newRepository()

        repo.setProgress("MangaBat", "m1", "c1", page = 1, chapterNumber = 12.5)

        assertEquals(12.5, repo.latestProgress("MangaBat", "m1")?.chapterNumber)
        assertEquals(12.5, repo.progress("MangaBat", "m1", "c1")?.chapterNumber)
    }

    @Test
    fun unreadCountWithNoProgressRowsEqualsTheFullChapterCount() = runTest {
        val repo = newRepository()
        repo.addToLibrary(MangaEntry(sourceId = "MangaBat", mangaId = "m1", title = "Solo Leveling"))

        repo.setChapterCount("MangaBat", "m1", 10)

        val item = repo.observeLibrary().first().single()
        assertEquals(10, item.unreadCount)
        assertNull(item.lastReadAt)
    }

    @Test
    fun unreadCountDropsByOnePerStartedChapterRegardlessOfFinished() = runTest {
        val repo = newRepository()
        repo.addToLibrary(MangaEntry(sourceId = "MangaBat", mangaId = "m1", title = "Solo Leveling"))
        repo.setChapterCount("MangaBat", "m1", 10)

        // c1 is only opened (not finished) — still counts as started, not unread.
        repo.setProgress("MangaBat", "m1", "c1", page = 0, finished = false)
        repo.setProgress("MangaBat", "m1", "c2", page = 5, finished = true)

        val item = repo.observeLibrary().first().single()
        assertEquals(8, item.unreadCount)
    }

    @Test
    fun unreadCountIsZeroWhenEveryChapterHasBeenStarted() = runTest {
        val repo = newRepository()
        repo.addToLibrary(MangaEntry(sourceId = "MangaBat", mangaId = "m1", title = "Solo Leveling"))
        repo.setChapterCount("MangaBat", "m1", 2)

        repo.setProgress("MangaBat", "m1", "c1", page = 0, finished = true)
        repo.setProgress("MangaBat", "m1", "c2", page = 0, finished = true)

        assertEquals(0, repo.observeLibrary().first().single().unreadCount)
    }

    @Test
    fun unreadCountWithZeroChapterCountStaysZeroNotNegative() = runTest {
        val repo = newRepository()
        repo.addToLibrary(MangaEntry(sourceId = "MangaBat", mangaId = "m1", title = "Solo Leveling"))

        // chapter_count defaults to 0 (never loaded via Details yet); a stray progress row must
        // not drive the MAX(0, ...) floor negative.
        repo.setProgress("MangaBat", "m1", "c1", page = 0, finished = true)

        assertEquals(0, repo.observeLibrary().first().single().unreadCount)
    }

    @Test
    fun lastReadAtIsNullUntilTheFirstProgressWriteThenTracksTheLatest() = runTest {
        val repo = newRepository()
        repo.addToLibrary(MangaEntry(sourceId = "MangaBat", mangaId = "m1", title = "Solo Leveling"))
        assertNull(repo.observeLibrary().first().single().lastReadAt)

        repo.setProgress("MangaBat", "m1", "c1", page = 1)
        repo.setProgress("MangaBat", "m1", "c2", page = 2)

        val item = repo.observeLibrary().first().single()
        val latest = repo.latestProgress("MangaBat", "m1")
        assertEquals(latest?.updatedAt, item.lastReadAt)
    }

    @Test
    fun addingToLibraryAgainPreservesTheChapterCountInsteadOfResettingIt() = runTest {
        val repo = newRepository()
        val entry = MangaEntry(sourceId = "MangaBat", mangaId = "m1", title = "Solo Leveling")
        repo.addToLibrary(entry)
        repo.setChapterCount("MangaBat", "m1", 42)

        // Re-adding an already-library manga happens on every chapter download of an existing
        // series (AppShell.onDownloadChapter), not just the first add.
        repo.addToLibrary(entry.copy(title = "Solo Leveling (renamed)"))

        assertEquals(42, repo.observeLibrary().first().single().unreadCount)
    }
}
