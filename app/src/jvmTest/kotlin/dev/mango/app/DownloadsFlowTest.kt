package dev.mango.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.MangaStatus
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class DownloadsFlowTest {
    @get:Rule
    val rule = createComposeRule()

    // "Unread" means "not finished" — an in-progress (opened but unfinished) chapter still
    // counts as unread for downloading; only a fully-finished chapter is excluded.
    @Test
    fun downloadUnreadEnqueuesOnlyChaptersThatAreNotFinished() {
        val library = FakeLibraryRepository()
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapters = listOf(
            Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null),
            Chapter(chapterId = "c2", number = 2.0, title = null, publishedAt = null),
            Chapter(chapterId = "c3", number = 3.0, title = null, publishedAt = null),
        )
        val catalog = FakeCatalogRepository(
            details = mapOf(("FlameComics" to "manga-1") to details),
            chapters = mapOf(("FlameComics" to "manga-1") to chapters),
        )
        runBlocking {
            library.setProgress("FlameComics", "manga-1", "c1", page = 3, finished = true)
            // c3 is opened-but-unfinished: the new semantics' load-bearing case — it has a
            // progress row (old "read") yet must still count as unread for downloading.
            library.setProgress("FlameComics", "manga-1", "c3", page = 1, finished = false)
        }
        var downloaded: List<Chapter>? = null

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreen(
                    sourceId = "FlameComics",
                    mangaId = "manga-1",
                    catalog = catalog,
                    library = library,
                    downloads = FakeDownloadManager(),
                    challengeSolver = FakeChallengeSolver(),
                    catalogCache = FakeCatalogCache(),
                    onOpenChapter = { _, _ -> },
                    onDownloadAll = { _, chs -> downloaded = chs },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Download unread").performClick()
        rule.waitForIdle()

        assertEquals(listOf("c2", "c3"), downloaded?.map { it.chapterId })
    }

    @Test
    fun downloadRangeFiltersChaptersByNumberInclusive() {
        val library = FakeLibraryRepository()
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapters = (1..5).map { n ->
            Chapter(chapterId = "c$n", number = n.toDouble(), title = null, publishedAt = null)
        }
        val catalog = FakeCatalogRepository(
            details = mapOf(("FlameComics" to "manga-1") to details),
            chapters = mapOf(("FlameComics" to "manga-1") to chapters),
        )
        var downloaded: List<Chapter>? = null

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreen(
                    sourceId = "FlameComics",
                    mangaId = "manga-1",
                    catalog = catalog,
                    library = library,
                    downloads = FakeDownloadManager(),
                    challengeSolver = FakeChallengeSolver(),
                    catalogCache = FakeCatalogCache(),
                    onOpenChapter = { _, _ -> },
                    onDownloadAll = { _, chs -> downloaded = chs },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Download range…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("From").performTextInput("2")
        rule.onNodeWithText("To").performTextInput("4")
        rule.waitForIdle()
        rule.onNodeWithText("Download").performClick()
        rule.waitForIdle()

        assertEquals(listOf("c2", "c3", "c4"), downloaded?.map { it.chapterId })
    }

    @Test
    fun downloadRangeNormalizesReversedBounds() {
        val library = FakeLibraryRepository()
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapters = (1..5).map { n ->
            Chapter(chapterId = "c$n", number = n.toDouble(), title = null, publishedAt = null)
        }
        val catalog = FakeCatalogRepository(
            details = mapOf(("FlameComics" to "manga-1") to details),
            chapters = mapOf(("FlameComics" to "manga-1") to chapters),
        )
        var downloaded: List<Chapter>? = null

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreen(
                    sourceId = "FlameComics",
                    mangaId = "manga-1",
                    catalog = catalog,
                    library = library,
                    downloads = FakeDownloadManager(),
                    challengeSolver = FakeChallengeSolver(),
                    catalogCache = FakeCatalogCache(),
                    onOpenChapter = { _, _ -> },
                    onDownloadAll = { _, chs -> downloaded = chs },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Download range…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("From").performTextInput("4")
        rule.onNodeWithText("To").performTextInput("2")
        rule.waitForIdle()
        rule.onNodeWithText("Download").performClick()
        rule.waitForIdle()

        assertEquals(listOf("c2", "c3", "c4"), downloaded?.map { it.chapterId })
    }

    @Test
    fun downloadedChapterRowShowsACheckmarkAndAnUndownloadedRowShowsTheDownloadArrow() {
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapters = listOf(
            Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null),
            Chapter(chapterId = "c2", number = 2.0, title = null, publishedAt = null),
        )

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreenContent(
                    details = details,
                    chapters = chapters,
                    inLibrary = false,
                    downloadedChapterIds = setOf("c1"),
                    onOpenChapter = { _, _ -> },
                    onDownloadChapter = { _, _ -> },
                    onDownloadAll = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("✓").assertExists()
        rule.onNodeWithText("↓").assertExists()
    }

    @Test
    fun downloadAllSkipsChaptersAlreadyOnDisk() {
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapters = listOf(
            Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null),
            Chapter(chapterId = "c2", number = 2.0, title = null, publishedAt = null),
        )
        var downloaded: List<Chapter>? = null

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreenContent(
                    details = details,
                    chapters = chapters,
                    inLibrary = false,
                    downloadedChapterIds = setOf("c1"),
                    onOpenChapter = { _, _ -> },
                    onDownloadChapter = { _, _ -> },
                    onDownloadAll = { _, chs -> downloaded = chs },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Download all").performClick()
        rule.waitForIdle()

        assertEquals(listOf("c2"), downloaded?.map { it.chapterId })
    }

    @Test
    fun clearStorageIsAbsentWithoutDownloadsAndConfirmingItAfterTheyAppearInvokesTheCallbackOnce() {
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        var clearCount = 0
        var hasDownloads by mutableStateOf(false)

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreenContent(
                    details = details,
                    chapters = emptyList(),
                    inLibrary = false,
                    hasDownloads = hasDownloads,
                    onOpenChapter = { _, _ -> },
                    onDownloadChapter = { _, _ -> },
                    onDownloadAll = { _, _ -> },
                    onClearStorage = { clearCount++ },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Clear storage").assertDoesNotExist()

        hasDownloads = true
        rule.waitForIdle()

        rule.onNodeWithText("Clear storage").assertExists()
        rule.onNodeWithText("Clear storage").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Clear").performClick()
        rule.waitForIdle()

        assertEquals(1, clearCount)
    }
}
