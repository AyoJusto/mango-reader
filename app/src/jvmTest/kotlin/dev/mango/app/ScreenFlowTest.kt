package dev.mango.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import dev.mango.core.domain.CachedManga
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.ChallengeRequiredException
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.HomeSection
import dev.mango.core.domain.LibraryItem
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.MangaStatus
import dev.mango.core.domain.Page
import dev.mango.core.domain.ReadProgress
import dev.mango.core.domain.SourceInfo
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * Wraps [FakeCatalogRepository] to count [search] calls. Kept local to this test file rather
 * than added to FakeCatalogRepository itself.
 */
private class CountingCatalogRepository(private val delegate: CatalogRepository) : CatalogRepository {
    var searchCallCount = 0
        private set

    override suspend fun installedSources(): List<SourceInfo> = delegate.installedSources()

    override suspend fun install(info: SourceInfo, bundleSha256: String) = delegate.install(info, bundleSha256)

    override suspend fun search(sourceId: String, query: String, page: Int): List<MangaEntry> {
        searchCallCount++
        return delegate.search(sourceId, query, page)
    }

    override suspend fun homeSections(sourceId: String): List<HomeSection> =
        delegate.homeSections(sourceId)

    override suspend fun details(sourceId: String, mangaId: String): MangaDetails =
        delegate.details(sourceId, mangaId)

    override suspend fun chapters(sourceId: String, mangaId: String): List<Chapter> =
        delegate.chapters(sourceId, mangaId)

    override suspend fun pages(sourceId: String, mangaId: String, chapterId: String): List<Page> =
        delegate.pages(sourceId, mangaId, chapterId)

    override suspend fun setUserAgent(sourceId: String, userAgent: String) =
        delegate.setUserAgent(sourceId, userAgent)

    override suspend fun uninstall(sourceId: String) = delegate.uninstall(sourceId)
}

/** A [details] fetch that never completes — proves a cached render doesn't wait on it. */
private class SuspendingCatalogRepository(private val delegate: CatalogRepository) : CatalogRepository by delegate {
    override suspend fun details(sourceId: String, mangaId: String): MangaDetails = awaitCancellation()
}

/** A [details] fetch that always fails with [exception] — proves stale content survives a bad revalidation. */
private class ThrowingCatalogRepository(
    private val delegate: CatalogRepository,
    private val exception: Exception,
) : CatalogRepository by delegate {
    override suspend fun details(sourceId: String, mangaId: String): MangaDetails = throw exception
}

/**
 * End-to-end navigation and interaction flows through [AppShell] and [DetailsScreen], backed
 * by [FakeLibraryRepository]/[FakeCatalogRepository]. CMP 1.11 uses StandardTestDispatcher —
 * every action that launches a coroutine is followed by [waitForIdle] before the next assert.
 */
class ScreenFlowTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun appShellStartsOnEmptyLibraryThenSidebarSwitchesToBrowse() {
        val library = FakeLibraryRepository()
        val catalog = FakeCatalogRepository(sources = listOf(SourceInfo("FlameComics", "FlameComics")))

        rule.setContent { TestAppShell(library, catalog, FakeDownloadManager()) }

        rule.onNodeWithText("Nothing here yet").assertExists()

        rule.navigateVia("Browse")

        rule.onNodeWithText("Search…").assertExists()
    }

    @Test
    fun typingAQueryAndSubmittingShowsCannedResults() {
        val library = FakeLibraryRepository()
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val catalog = FakeCatalogRepository(
            sources = listOf(SourceInfo("FlameComics", "FlameComics")),
            results = mapOf("solo" to listOf(entry)),
        )

        rule.setContent { TestAppShell(library, catalog, FakeDownloadManager()) }

        rule.navigateVia("Browse")

        rule.onNodeWithText("Search…").performTextInput("solo")
        rule.waitForIdle()
        rule.onNodeWithText("solo").performImeAction()
        rule.waitForIdle()

        rule.onNodeWithText("Solo Leveling").assertExists()
    }

    @Test
    fun switchingTabsAwayFromBrowseAndBackKeepsQueryAndResultsWithoutResearching() {
        val library = FakeLibraryRepository()
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val fake = FakeCatalogRepository(
            sources = listOf(SourceInfo("FlameComics", "FlameComics")),
            results = mapOf("solo" to listOf(entry)),
        )
        val catalog = CountingCatalogRepository(fake)

        rule.setContent { TestAppShell(library, catalog, FakeDownloadManager()) }

        rule.navigateVia("Browse")

        rule.onNodeWithText("Search…").performTextInput("solo")
        rule.waitForIdle()
        rule.onNodeWithText("solo").performImeAction()
        rule.waitForIdle()

        rule.onNodeWithText("Solo Leveling").assertExists()

        rule.navigateVia("Library")
        rule.navigateVia("Browse")

        rule.onNodeWithText("solo").assertExists()
        rule.onNodeWithText("Solo Leveling").assertExists()
        assertEquals(1, catalog.searchCallCount)
    }

    @Test
    fun clickingAddToLibraryCallsThroughAndFlipsTheButtonLabel() {
        val library = FakeLibraryRepository()
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val catalog = FakeCatalogRepository(
            details = mapOf(("FlameComics" to "manga-1") to details),
            chapters = mapOf(("FlameComics" to "manga-1") to emptyList()),
        )

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
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Add to library").assertExists()
        rule.onNodeWithText("Add to library").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("In library — remove").assertExists()
    }

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
                    onToggleLibrary = {},
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
                    onToggleLibrary = {},
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
                    onToggleLibrary = {},
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

    // The Continue button's three variants — no progress, in-progress, and finished.
    @Test
    fun startReadingButtonOpensTheFirstChapterWhenThereIsNoProgressYet() {
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapters = listOf(
            Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null),
            Chapter(chapterId = "c2", number = 2.0, title = null, publishedAt = null),
        )
        var opened: Chapter? = null

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreenContent(
                    details = details,
                    chapters = chapters,
                    inLibrary = false,
                    onToggleLibrary = {},
                    onOpenChapter = { chapter, _ -> opened = chapter },
                    onDownloadChapter = { _, _ -> },
                    onDownloadAll = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Start reading").performClick()
        rule.waitForIdle()

        assertEquals("c1", opened?.chapterId)
    }

    @Test
    fun continueButtonShowsTheSavedPageAndReopensTheUnfinishedChapter() {
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapters = listOf(
            Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null),
            Chapter(chapterId = "c2", number = 2.0, title = null, publishedAt = null),
        )
        var opened: Chapter? = null

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreenContent(
                    details = details,
                    chapters = chapters,
                    inLibrary = false,
                    latestProgress = ReadProgress(chapterId = "c1", page = 2, updatedAt = Clock.System.now(), finished = false),
                    onToggleLibrary = {},
                    onOpenChapter = { chapter, _ -> opened = chapter },
                    onDownloadChapter = { _, _ -> },
                    onDownloadAll = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Continue — Ch. 1 · p. 3").performClick()
        rule.waitForIdle()

        assertEquals("c1", opened?.chapterId)
    }

    @Test
    fun continueButtonAdvancesToTheNextChapterOnceTheLatestOneIsFinished() {
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapters = listOf(
            Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null),
            Chapter(chapterId = "c2", number = 2.0, title = null, publishedAt = null),
        )
        var opened: Chapter? = null

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreenContent(
                    details = details,
                    chapters = chapters,
                    inLibrary = false,
                    latestProgress = ReadProgress(chapterId = "c1", page = 4, updatedAt = Clock.System.now(), finished = true),
                    onToggleLibrary = {},
                    onOpenChapter = { chapter, _ -> opened = chapter },
                    onDownloadChapter = { _, _ -> },
                    onDownloadAll = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Continue — Ch. 2").performClick()
        rule.waitForIdle()

        assertEquals("c2", opened?.chapterId)
    }

    // Warm-cache stale-while-revalidate: a hit paints the persisted copy immediately, then a
    // live fetch always runs in the background and only ever improves or is silently ignored.
    @Test
    fun warmCacheRendersImmediatelyWhileTheLiveFetchNeverCompletes() {
        val library = FakeLibraryRepository()
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapters = listOf(Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null))
        val catalogCache = FakeCatalogCache(mapOf(("FlameComics" to "manga-1") to CachedManga(details, chapters)))
        val catalog = SuspendingCatalogRepository(FakeCatalogRepository())

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreen(
                    sourceId = "FlameComics",
                    mangaId = "manga-1",
                    catalog = catalog,
                    library = library,
                    downloads = FakeDownloadManager(),
                    challengeSolver = FakeChallengeSolver(),
                    catalogCache = catalogCache,
                    onOpenChapter = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Solo Leveling").assertExists()
        rule.onNodeWithText("1 chapters").assertExists()
    }

    @Test
    fun backgroundRevalidationReplacesTheStaleChapterListAndWritesThroughToTheCache() {
        val library = FakeLibraryRepository()
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val staleChapters = listOf(Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null))
        val freshChapters = staleChapters + Chapter(chapterId = "c2", number = 2.0, title = null, publishedAt = null)
        val catalogCache = FakeCatalogCache(mapOf(("FlameComics" to "manga-1") to CachedManga(details, staleChapters)))
        val catalog = FakeCatalogRepository(
            details = mapOf(("FlameComics" to "manga-1") to details),
            chapters = mapOf(("FlameComics" to "manga-1") to freshChapters),
        )

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreen(
                    sourceId = "FlameComics",
                    mangaId = "manga-1",
                    catalog = catalog,
                    library = library,
                    downloads = FakeDownloadManager(),
                    challengeSolver = FakeChallengeSolver(),
                    catalogCache = catalogCache,
                    onOpenChapter = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("2 chapters").assertExists()
        assertEquals(1, catalogCache.putCount)
    }

    @Test
    fun warmCacheSwallowsARevalidationFailureAndKeepsTheStaleContent() {
        val library = FakeLibraryRepository()
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapters = listOf(Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null))
        val catalogCache = FakeCatalogCache(mapOf(("FlameComics" to "manga-1") to CachedManga(details, chapters)))
        val catalog = ThrowingCatalogRepository(FakeCatalogRepository(), RuntimeException("boom"))

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreen(
                    sourceId = "FlameComics",
                    mangaId = "manga-1",
                    catalog = catalog,
                    library = library,
                    downloads = FakeDownloadManager(),
                    challengeSolver = FakeChallengeSolver(),
                    catalogCache = catalogCache,
                    onOpenChapter = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Solo Leveling").assertExists()
        rule.onNodeWithText("boom").assertDoesNotExist()
    }

    @Test
    fun warmCacheSwallowsAChallengeFailureAndShowsNoChallengeCard() {
        val library = FakeLibraryRepository()
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapters = listOf(Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null))
        val catalogCache = FakeCatalogCache(mapOf(("FlameComics" to "manga-1") to CachedManga(details, chapters)))
        val catalog = ThrowingCatalogRepository(
            FakeCatalogRepository(),
            ChallengeRequiredException(sourceId = "FlameComics", url = "https://example.com"),
        )

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreen(
                    sourceId = "FlameComics",
                    mangaId = "manga-1",
                    catalog = catalog,
                    library = library,
                    downloads = FakeDownloadManager(),
                    challengeSolver = FakeChallengeSolver(),
                    catalogCache = catalogCache,
                    onOpenChapter = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Solo Leveling").assertExists()
        rule.onNodeWithText("Solve challenge").assertDoesNotExist()
    }

    @Test
    fun warmCacheFiresAutoContinueWithoutTheLiveFetchCompleting() {
        val library = FakeLibraryRepository()
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapters = listOf(
            Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null),
            Chapter(chapterId = "c2", number = 2.0, title = null, publishedAt = null),
        )
        val catalogCache = FakeCatalogCache(mapOf(("FlameComics" to "manga-1") to CachedManga(details, chapters)))
        val catalog = SuspendingCatalogRepository(FakeCatalogRepository())
        // autoContinue only ever resumes existing progress — nothing to continue without it.
        runBlocking { library.setProgress("FlameComics", "manga-1", "c1", page = 2, finished = false) }
        var opened: Chapter? = null

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreen(
                    sourceId = "FlameComics",
                    mangaId = "manga-1",
                    catalog = catalog,
                    library = library,
                    downloads = FakeDownloadManager(),
                    challengeSolver = FakeChallengeSolver(),
                    catalogCache = catalogCache,
                    autoContinue = true,
                    onOpenChapter = { chapter, _ -> opened = chapter },
                )
            }
        }
        rule.waitForIdle()

        assertEquals("c1", opened?.chapterId)
    }

    // An auto-continue pass-through is headless: the veil renders, never the Details content,
    // and the jump still fires off the cached chapter list.
    @Test
    fun autoContinuePassThroughNeverRendersDetailsContent() {
        val library = FakeLibraryRepository()
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapters = listOf(
            Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null),
            Chapter(chapterId = "c2", number = 2.0, title = null, publishedAt = null),
        )
        val catalogCache = FakeCatalogCache(mapOf(("FlameComics" to "manga-1") to CachedManga(details, chapters)))
        val catalog = SuspendingCatalogRepository(FakeCatalogRepository())
        runBlocking { library.setProgress("FlameComics", "manga-1", "c1", page = 2, finished = false) }
        var opened: Chapter? = null

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreen(
                    sourceId = "FlameComics",
                    mangaId = "manga-1",
                    catalog = catalog,
                    library = library,
                    downloads = FakeDownloadManager(),
                    challengeSolver = FakeChallengeSolver(),
                    catalogCache = catalogCache,
                    autoContinue = true,
                    onOpenChapter = { chapter, _ -> opened = chapter },
                )
            }
        }
        rule.waitForIdle()

        assertEquals("c1", opened?.chapterId)
        rule.onNodeWithText("Solo Leveling").assertDoesNotExist()
    }

    // The veil must not strand the user when there is nothing to continue into: once the
    // chapter list settles without a target, the normal Details render takes over.
    @Test
    fun autoContinueVeilFallsBackToDetailsWhenThereIsNothingToContinueInto() {
        val library = FakeLibraryRepository()
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapters = listOf(Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null))
        val catalogCache = FakeCatalogCache(mapOf(("FlameComics" to "manga-1") to CachedManga(details, chapters)))
        val catalog = SuspendingCatalogRepository(FakeCatalogRepository())
        var opened: Chapter? = null

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreen(
                    sourceId = "FlameComics",
                    mangaId = "manga-1",
                    catalog = catalog,
                    library = library,
                    downloads = FakeDownloadManager(),
                    challengeSolver = FakeChallengeSolver(),
                    catalogCache = catalogCache,
                    autoContinue = true,
                    onOpenChapter = { chapter, _ -> opened = chapter },
                )
            }
        }
        rule.waitForIdle()

        assertEquals(null, opened)
        rule.onNodeWithText("Solo Leveling").assertExists()
    }

    // A cold-cache auto-continue that fails must surface the error card, not hold the veil.
    @Test
    fun coldCacheAutoContinueFailureShowsTheErrorCardNotTheVeil() {
        val library = FakeLibraryRepository()
        val catalogCache = FakeCatalogCache()
        val catalog = ThrowingCatalogRepository(FakeCatalogRepository(), RuntimeException("boom"))

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreen(
                    sourceId = "FlameComics",
                    mangaId = "manga-1",
                    catalog = catalog,
                    library = library,
                    downloads = FakeDownloadManager(),
                    challengeSolver = FakeChallengeSolver(),
                    catalogCache = catalogCache,
                    autoContinue = true,
                    onOpenChapter = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("boom").assertExists()
    }

    // Guards that the pre-cache path is untouched: nothing cached means a fetch failure still
    // surfaces the error card instead of being swallowed.
    @Test
    fun emptyCacheFailureStillShowsTheErrorCard() {
        val library = FakeLibraryRepository()
        val catalogCache = FakeCatalogCache()
        val catalog = ThrowingCatalogRepository(FakeCatalogRepository(), RuntimeException("boom"))

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreen(
                    sourceId = "FlameComics",
                    mangaId = "manga-1",
                    catalog = catalog,
                    library = library,
                    downloads = FakeDownloadManager(),
                    challengeSolver = FakeChallengeSolver(),
                    catalogCache = catalogCache,
                    onOpenChapter = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("boom").assertExists()
    }

    // A chapter first seen after the series' last open is NEW; one seen before it is not —
    // markOpened must still fire so the next visit's snapshot moves forward.
    @Test
    fun aChapterFirstSeenAfterTheLastOpenShowsExactlyOneNewChip() {
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapters = listOf(
            Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null),
            Chapter(chapterId = "c2", number = 2.0, title = null, publishedAt = null),
        )
        val lastOpenedAt = Instant.fromEpochMilliseconds(1_000)
        val firstSeenAt = mapOf(
            "c1" to Instant.fromEpochMilliseconds(500), // seen before last open: not new
            "c2" to Instant.fromEpochMilliseconds(2_000), // seen after last open: new
        )
        val catalogCache = FakeCatalogCache(
            mapOf(("FlameComics" to "manga-1") to CachedManga(details, chapters, firstSeenAt = firstSeenAt)),
        )
        val catalog = FakeCatalogRepository(
            details = mapOf(("FlameComics" to "manga-1") to details),
            chapters = mapOf(("FlameComics" to "manga-1") to chapters),
        )
        val library = FakeLibraryRepository(listOf(LibraryItem(entry, Clock.System.now(), lastOpenedAt = lastOpenedAt)))

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreen(
                    sourceId = "FlameComics",
                    mangaId = "manga-1",
                    catalog = catalog,
                    library = library,
                    downloads = FakeDownloadManager(),
                    challengeSolver = FakeChallengeSolver(),
                    catalogCache = catalogCache,
                    onOpenChapter = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()

        rule.onAllNodesWithText("NEW").assertCountEquals(1)
        assertNotNull(library.openedAt["FlameComics" to "manga-1"])
    }

    // A series that has never been opened (null lastOpenedAt) has nothing to compare
    // first-seen stamps against, so no chapter can be NEW regardless of when it was cached.
    @Test
    fun aSeriesNeverOpenedShowsNoNewChips() {
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapters = listOf(Chapter(chapterId = "c1", number = 1.0, title = null, publishedAt = null))
        val catalogCache = FakeCatalogCache(
            mapOf(
                ("FlameComics" to "manga-1") to CachedManga(
                    details,
                    chapters,
                    firstSeenAt = mapOf("c1" to Instant.fromEpochMilliseconds(2_000)),
                ),
            ),
        )
        val catalog = FakeCatalogRepository(
            details = mapOf(("FlameComics" to "manga-1") to details),
            chapters = mapOf(("FlameComics" to "manga-1") to chapters),
        )
        val library = FakeLibraryRepository(listOf(LibraryItem(entry, Clock.System.now())))

        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                DetailsScreen(
                    sourceId = "FlameComics",
                    mangaId = "manga-1",
                    catalog = catalog,
                    library = library,
                    downloads = FakeDownloadManager(),
                    challengeSolver = FakeChallengeSolver(),
                    catalogCache = catalogCache,
                    onOpenChapter = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()

        rule.onAllNodesWithText("NEW").assertCountEquals(0)
    }
}
