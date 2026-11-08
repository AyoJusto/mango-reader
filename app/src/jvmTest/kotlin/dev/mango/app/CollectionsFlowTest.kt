package dev.mango.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import dev.mango.core.domain.LibraryItem
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.MangaStatus
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class CollectionsFlowTest {
    @get:Rule
    val rule = createComposeRule()

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

        rule.onNodeWithText("In library ✓").assertExists()
    }

    @Test
    fun addingToLibraryFilesIntoTheDefaultCollectionAndTheToastNamesIt() {
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

        rule.onNodeWithTag("split-button-main").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("In library ✓").assertExists()
        rule.onNode(hasText("Reading", substring = true)).assertExists()
        val stored = runBlocking { library.observeLibrary().first() }.single()
        assertEquals(setOf(1L), stored.collectionIds)
    }

    @Test
    fun togglingPickerCheckboxesUpdatesMembershipAndUncheckingEveryBoxKeepsTheSeriesInTheLibrary() {
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val library = FakeLibraryRepository(listOf(LibraryItem(entry, Clock.System.now(), collectionIds = setOf(1))))
        runBlocking { library.createCollection("Later") }
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

        rule.onNodeWithTag("split-button-arrow").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Later").performClick()
        rule.waitForIdle()

        val afterAdd = runBlocking { library.observeLibrary().first() }.single()
        assertEquals(setOf(1L, 2L), afterAdd.collectionIds)

        rule.onNodeWithText("Reading").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Later").performClick()
        rule.waitForIdle()

        val afterRemoveAll = runBlocking { library.observeLibrary().first() }
        assertTrue(afterRemoveAll.any { it.entry.mangaId == "manga-1" })
        assertEquals(emptySet<Long>(), afterRemoveAll.single().collectionIds)
    }

    @Test
    fun togglingAPickerCheckboxOnANotInLibrarySeriesAddsItWithExactlyThatMembership() {
        val library = FakeLibraryRepository()
        runBlocking { library.createCollection("Later") }
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

        rule.onNodeWithTag("split-button-arrow").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Later").performClick()
        rule.waitForIdle()

        val stored = runBlocking { library.observeLibrary().first() }.single()
        assertEquals("manga-1", stored.entry.mangaId)
        assertEquals(setOf(2L), stored.collectionIds)
    }

    @Test
    fun creatingACollectionInThePickerChecksItAndFilesTheSeriesAlongsideWhatWasAlreadyChecked() {
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val library = FakeLibraryRepository(listOf(LibraryItem(entry, Clock.System.now(), collectionIds = setOf(1))))
        runBlocking { library.createCollection("Later") }
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

        rule.onNodeWithTag("split-button-arrow").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Later").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("＋ New collection…").performClick()
        rule.waitForIdle()
        val field = rule.onNode(hasSetTextAction())
        field.performTextInput("Finished")
        rule.waitForIdle()
        field.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        val stored = runBlocking { library.observeLibrary().first() }.single()
        assertEquals(setOf(1L, 2L, 3L), stored.collectionIds)
    }

    @Test
    fun removeFromLibraryRowInThePickerRemovesTheSeries() {
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val library = FakeLibraryRepository(listOf(LibraryItem(entry, Clock.System.now(), collectionIds = setOf(1))))
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

        rule.onNodeWithTag("split-button-arrow").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Remove from library").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Add to library").assertExists()
        val remaining = runBlocking { library.observeLibrary().first() }
        assertTrue(remaining.none { it.entry.mangaId == "manga-1" })
    }
}
