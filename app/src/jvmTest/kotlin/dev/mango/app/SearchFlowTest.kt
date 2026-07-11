package dev.mango.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.Page
import dev.mango.core.domain.SourceInfo
import org.junit.Rule
import org.junit.Test

/**
 * Canned [CatalogRepository] that answers [search] differently per sourceId — unlike
 * [FakeCatalogRepository], which only keys results by query. Needed here because the Search tab
 * fans the same query out to every installed source and each source must be able to behave
 * independently (one succeeds, one throws).
 */
private class PerSourceCatalogRepository(
    private val sources: List<SourceInfo>,
    private val resultsBySource: Map<String, List<MangaEntry>> = emptyMap(),
    private val failuresBySource: Map<String, Throwable> = emptyMap(),
) : CatalogRepository {
    override suspend fun installedSources(): List<SourceInfo> = sources

    override suspend fun install(info: SourceInfo, bundleSha256: String) = Unit

    override suspend fun search(sourceId: String, query: String, page: Int): List<MangaEntry> {
        failuresBySource[sourceId]?.let { throw it }
        return resultsBySource[sourceId] ?: emptyList()
    }

    override suspend fun details(sourceId: String, mangaId: String): MangaDetails =
        error("PerSourceCatalogRepository.details is not stubbed")

    override suspend fun chapters(sourceId: String, mangaId: String): List<Chapter> =
        error("PerSourceCatalogRepository.chapters is not stubbed")

    override suspend fun pages(sourceId: String, mangaId: String, chapterId: String): List<Page> =
        error("PerSourceCatalogRepository.pages is not stubbed")

    override suspend fun setUserAgent(sourceId: String, userAgent: String) = Unit
}

/**
 * End-to-end flow through [AppShell]'s Search tab, backed by [PerSourceCatalogRepository] so one
 * source can succeed while another fails without either affecting the other's rendering.
 */
class SearchFlowTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun searchFansOutSoOneSourceFailingDoesNotHideAnothersResults() {
        val library = FakeLibraryRepository()
        val entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling")
        val catalog = PerSourceCatalogRepository(
            sources = listOf(SourceInfo("FlameComics", "FlameComics"), SourceInfo("MangaBat", "MangaBat")),
            resultsBySource = mapOf("FlameComics" to listOf(entry)),
            failuresBySource = mapOf("MangaBat" to RuntimeException("site unreachable")),
        )

        rule.setContent { MangoTheme { AppShell(library, catalog, FakeDownloadManager()) } }

        rule.onNodeWithText("Search").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Search all sources…").performTextInput("solo")
        rule.waitForIdle()
        rule.onNodeWithText("solo").performImeAction()
        rule.waitForIdle()

        rule.onNodeWithText("Solo Leveling").assertExists()
        rule.onNodeWithText("site unreachable").assertExists()
    }

    @Test
    fun everyInstalledSourceGetsAFilterChip() {
        val library = FakeLibraryRepository()
        val catalog = PerSourceCatalogRepository(
            sources = listOf(SourceInfo("FlameComics", "FlameComics"), SourceInfo("MangaBat", "MangaBat")),
        )

        rule.setContent { MangoTheme { AppShell(library, catalog, FakeDownloadManager()) } }

        rule.onNodeWithText("Search").performClick()
        rule.waitForIdle()

        // Pre-search the screen shows only the idle hint, so each source's name renders
        // exactly once: as its FilterChip label.
        rule.onAllNodesWithText("FlameComics").assertCountEquals(1)
        rule.onAllNodesWithText("MangaBat").assertCountEquals(1)
        rule.onNodeWithText("Search across all installed sources").assertExists()
    }
}
