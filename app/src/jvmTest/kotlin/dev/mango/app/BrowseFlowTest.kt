package dev.mango.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.ChallengeRequiredException
import dev.mango.core.domain.HomeSection
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.Page
import dev.mango.core.domain.SourceInfo
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Canned [CatalogRepository] whose [homeSections] throws a scripted failure on the FIRST call
 * for a source, then answers from [sectionsBySource] — [FakeCatalogRepository] can express
 * neither the throw (challenge rendering) nor the throw-then-succeed solve flow.
 */
private class ScriptedHomeSectionsCatalogRepository(
    private val sources: List<SourceInfo>,
    private val sectionsBySource: Map<String, List<HomeSection>> = emptyMap(),
    failuresBySource: Map<String, Throwable> = emptyMap(),
) : CatalogRepository {
    private val pendingFailures = failuresBySource.toMutableMap()

    override suspend fun installedSources(): List<SourceInfo> = sources

    override suspend fun install(info: SourceInfo, bundleSha256: String) = Unit

    override suspend fun search(sourceId: String, query: String, page: Int): List<MangaEntry> = emptyList()

    override suspend fun homeSections(sourceId: String): List<HomeSection> {
        pendingFailures.remove(sourceId)?.let { throw it }
        return sectionsBySource[sourceId] ?: emptyList()
    }

    override suspend fun details(sourceId: String, mangaId: String): MangaDetails =
        error("ScriptedHomeSectionsCatalogRepository.details is not stubbed")

    override suspend fun chapters(sourceId: String, mangaId: String): List<Chapter> =
        error("ScriptedHomeSectionsCatalogRepository.chapters is not stubbed")

    override suspend fun pages(sourceId: String, mangaId: String, chapterId: String): List<Page> =
        error("ScriptedHomeSectionsCatalogRepository.pages is not stubbed")

    override suspend fun setUserAgent(sourceId: String, userAgent: String) = Unit
}

/**
 * Wraps any [CatalogRepository] to count [homeSections] calls per sourceId — mirror of
 * [ScreenFlowTest]'s CountingCatalogRepository pattern, kept local for the same reason.
 */
private class SectionCountingCatalogRepository(private val delegate: CatalogRepository) : CatalogRepository {
    val homeSectionsCalls = mutableMapOf<String, Int>()

    override suspend fun installedSources(): List<SourceInfo> = delegate.installedSources()

    override suspend fun install(info: SourceInfo, bundleSha256: String) = delegate.install(info, bundleSha256)

    override suspend fun search(sourceId: String, query: String, page: Int): List<MangaEntry> =
        delegate.search(sourceId, query, page)

    override suspend fun homeSections(sourceId: String): List<HomeSection> {
        homeSectionsCalls[sourceId] = (homeSectionsCalls[sourceId] ?: 0) + 1
        return delegate.homeSections(sourceId)
    }

    override suspend fun details(sourceId: String, mangaId: String): MangaDetails =
        delegate.details(sourceId, mangaId)

    override suspend fun chapters(sourceId: String, mangaId: String): List<Chapter> =
        delegate.chapters(sourceId, mangaId)

    override suspend fun pages(sourceId: String, mangaId: String, chapterId: String): List<Page> =
        delegate.pages(sourceId, mangaId, chapterId)

    override suspend fun setUserAgent(sourceId: String, userAgent: String) =
        delegate.setUserAgent(sourceId, userAgent)
}

/**
 * End-to-end flow through [AppShell]'s Browse tab, exercising the M5(b) discover/home sections
 * rendering, its precedence over stale search results when switching source chips, the session
 * cache, and the Cloudflare-challenge/solve path for a source's sections fetch.
 */
class BrowseFlowTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun enteringBrowseShowsTheSelectedSourcesSectionAndItemTitles() {
        val library = FakeLibraryRepository()
        val popular = HomeSection(
            id = "popular",
            title = "Popular",
            items = listOf(MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")),
        )
        val catalog = FakeCatalogRepository(
            sources = listOf(SourceInfo("FlameComics", "FlameComics")),
            sectionsBySource = mapOf("FlameComics" to listOf(popular)),
        )

        rule.setContent { MangoTheme { AppShell(library, catalog, FakeDownloadManager()) } }

        rule.onNodeWithText("Browse").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Popular").assertExists()
        rule.onNodeWithText("Solo Leveling").assertExists()
    }

    @Test
    fun aSourceWithNoHomeSectionsShowsTheNoDiscoverSectionsHint() {
        val library = FakeLibraryRepository()
        val catalog = FakeCatalogRepository(sources = listOf(SourceInfo("FlameComics", "FlameComics")))

        rule.setContent { MangoTheme { AppShell(library, catalog, FakeDownloadManager()) } }

        rule.onNodeWithText("Browse").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("No discover sections — search this source instead").assertExists()
    }

    @Test
    fun switchingChipsShowsThatSourcesSectionsNotTheOtherSourcesStaleResults() {
        val library = FakeLibraryRepository()
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val popular = HomeSection(
            id = "popular",
            title = "Popular",
            items = listOf(MangaEntry(sourceId = "MangaBat", mangaId = "manga-2", title = "Tower of God")),
        )
        val catalog = FakeCatalogRepository(
            sources = listOf(SourceInfo("FlameComics", "FlameComics"), SourceInfo("MangaBat", "MangaBat")),
            results = mapOf("solo" to listOf(entry)),
            sectionsBySource = mapOf("MangaBat" to listOf(popular)),
        )

        rule.setContent { MangoTheme { AppShell(library, catalog, FakeDownloadManager()) } }

        rule.onNodeWithText("Browse").performClick()
        rule.waitForIdle()

        // search source A (FlameComics, selected by default)
        rule.onNodeWithText("Search…").performTextInput("solo")
        rule.waitForIdle()
        rule.onNodeWithText("solo").performImeAction()
        rule.waitForIdle()
        rule.onNodeWithText("Solo Leveling").assertExists()

        // switching to B shows B's sections, not A's stale results grid
        rule.onNodeWithText("MangaBat").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Tower of God").assertExists()
        rule.onNodeWithText("Solo Leveling").assertDoesNotExist()

        // switching back to A shows A's results again (searchedSourceId was never cleared)
        rule.onNodeWithText("FlameComics").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Solo Leveling").assertExists()
    }

    @Test
    fun submittingABlankQueryAfterASearchReturnsToSectionsMode() {
        val library = FakeLibraryRepository()
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val catalog = FakeCatalogRepository(
            sources = listOf(SourceInfo("FlameComics", "FlameComics")),
            results = mapOf("solo" to listOf(entry)),
        )

        rule.setContent { MangoTheme { AppShell(library, catalog, FakeDownloadManager()) } }

        rule.onNodeWithText("Browse").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Search…").performTextInput("solo")
        rule.waitForIdle()
        rule.onNodeWithText("solo").performImeAction()
        rule.waitForIdle()
        rule.onNodeWithText("Solo Leveling").assertExists()

        rule.onNodeWithText("solo").performTextClearance()
        rule.waitForIdle()
        rule.onNodeWithText("Search…").performImeAction()
        rule.waitForIdle()

        rule.onNodeWithText("No discover sections — search this source instead").assertExists()
        rule.onNodeWithText("Solo Leveling").assertDoesNotExist()
    }

    @Test
    fun aChallengeOnSectionsFetchRendersTheErrorAndASolveButton() {
        val library = FakeLibraryRepository()
        val catalog = ScriptedHomeSectionsCatalogRepository(
            sources = listOf(SourceInfo("FlameComics", "FlameComics")),
            failuresBySource = mapOf(
                "FlameComics" to ChallengeRequiredException("FlameComics", "https://flamecomics.example/challenge"),
            ),
        )

        rule.setContent { MangoTheme { AppShell(library, catalog, FakeDownloadManager()) } }

        rule.onNodeWithText("Browse").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Protected by Cloudflare").assertExists()
        rule.onNodeWithText("Solve challenge").assertExists()
    }

    @Test
    fun revisitingAChipDoesNotRefetchItsCachedSections() {
        val library = FakeLibraryRepository()
        val popular = HomeSection(
            id = "popular",
            title = "Popular",
            items = listOf(MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")),
        )
        val catalog = SectionCountingCatalogRepository(
            FakeCatalogRepository(
                sources = listOf(SourceInfo("FlameComics", "FlameComics"), SourceInfo("MangaBat", "MangaBat")),
                sectionsBySource = mapOf("FlameComics" to listOf(popular)),
            ),
        )

        rule.setContent { MangoTheme { AppShell(library, catalog, FakeDownloadManager()) } }

        rule.onNodeWithText("Browse").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Popular").assertExists()

        rule.onNodeWithText("MangaBat").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("FlameComics").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Popular").assertExists()
        assertEquals(1, catalog.homeSectionsCalls["FlameComics"])
    }

    @Test
    fun solvingASectionsChallengeRefetchesOnlyThatSourcesSections() {
        val library = FakeLibraryRepository()
        val popular = HomeSection(
            id = "popular",
            title = "Popular",
            items = listOf(MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")),
        )
        val solver = FakeChallengeSolver(result = true)
        val catalog = SectionCountingCatalogRepository(
            ScriptedHomeSectionsCatalogRepository(
                sources = listOf(SourceInfo("FlameComics", "FlameComics"), SourceInfo("MangaBat", "MangaBat")),
                sectionsBySource = mapOf("FlameComics" to listOf(popular)),
                failuresBySource = mapOf(
                    "FlameComics" to
                        ChallengeRequiredException("FlameComics", "https://flamecomics.example/challenge"),
                ),
            ),
        )

        rule.setContent {
            MangoTheme { AppShell(library, catalog, FakeDownloadManager(), challengeSolver = solver) }
        }

        rule.onNodeWithText("Browse").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Protected by Cloudflare").assertExists()

        rule.onNodeWithText("Solve challenge").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Popular").assertExists()
        rule.onNodeWithText("Solo Leveling").assertExists()
        // refetch hit only the solved source: initial challenge + post-solve retry, and the
        // never-selected source was never fetched at all (polite-scraper rule)
        assertEquals(2, catalog.homeSectionsCalls["FlameComics"])
        assertEquals(0, catalog.homeSectionsCalls["MangaBat"] ?: 0)
        assertEquals(1, solver.solved.size)
    }

    @Test
    fun zeroInstalledSourcesShowsTheNoSourcesHintNotTheSectionsHint() {
        val library = FakeLibraryRepository()
        val catalog = FakeCatalogRepository()

        rule.setContent { MangoTheme { AppShell(library, catalog, FakeDownloadManager()) } }

        rule.onNodeWithText("Browse").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("No sources installed — add one from the Extensions tab").assertExists()
        rule.onNodeWithText("No discover sections — search this source instead").assertDoesNotExist()
    }
}
