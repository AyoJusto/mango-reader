package dev.mango.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.mango.core.domain.CachedManga
import dev.mango.core.domain.ChallengeRequiredException
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.LibraryItem
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.MangaStatus
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class DetailsCacheFlowTest {
    @get:Rule
    val rule = createComposeRule()

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
