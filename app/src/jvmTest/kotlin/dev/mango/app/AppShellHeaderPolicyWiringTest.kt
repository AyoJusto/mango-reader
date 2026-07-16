package dev.mango.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.DownloadManager
import dev.mango.core.domain.LibraryRepository
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.MangaStatus
import dev.mango.core.domain.Page
import dev.mango.core.domain.SourceHeaderPolicy
import dev.mango.core.domain.SourceInfo
import dev.mango.core.engine.DefaultSourceHeaderPolicy
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

private const val SOURCE_ID = "FlameComics"
private const val MANGA_ID = "manga-1"
private const val CHAPTER_ID = "ch-1"

/**
 * [AppShell] with a hoisted sidebar (needed for [navigateVia]) plus [SourceHeaderPolicy] and a
 * reader page hook wired straight through — the same optional-hook idiom [TestAppShell] already
 * uses for its other ports, kept local to this file since no other flow test needs either hook.
 */
@Composable
private fun HeaderPolicyTestShell(
    library: LibraryRepository,
    catalog: CatalogRepository,
    downloads: DownloadManager,
    headerPolicy: SourceHeaderPolicy,
    onPageDelivered: (Page) -> Unit,
) {
    var sidebarOpen by remember { mutableStateOf(false) }
    ProvideMangoTheme(MangoDark) {
        AppShell(
            library = library,
            catalog = catalog,
            downloads = downloads,
            challengeSolver = FakeChallengeSolver(),
            headerPolicy = headerPolicy,
            sidebarOpen = sidebarOpen,
            onSidebarChange = { sidebarOpen = it },
            readerPageContent = { page -> onPageDelivered(page) },
        )
    }
}

/**
 * Proves [AppShell] actually threads its own `headerPolicy` parameter into the `ReaderScreen`
 * call in its Reader branch. Navigates Browse -> Details -> Reader through real [AppShell]
 * composition (not a direct [ReaderScreen] composition, which [ReaderFlowTest] already covers
 * and which cannot catch a dropped argument at the AppShell call site).
 */
class AppShellHeaderPolicyWiringTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun headerPolicyPassedToAppShellReachesPagesInTheReaderBranch() {
        val entry = MangaEntry(sourceId = SOURCE_ID, mangaId = MANGA_ID, title = "Solo Leveling")
        val details = MangaDetails(entry = entry, status = MangaStatus.ONGOING)
        val chapter = Chapter(CHAPTER_ID, number = 1.0)
        val pages = listOf(Page(index = 0, url = "https://example.test/0.jpg"))
        val catalog = FakeCatalogRepository(
            sources = listOf(SourceInfo(SOURCE_ID, SOURCE_ID)),
            results = mapOf("solo" to listOf(entry)),
            details = mapOf((SOURCE_ID to MANGA_ID) to details),
            chapters = mapOf((SOURCE_ID to MANGA_ID) to listOf(chapter)),
            pages = mapOf(Triple(SOURCE_ID, MANGA_ID, CHAPTER_ID) to pages),
        )
        val library = FakeLibraryRepository()
        val policy = DefaultSourceHeaderPolicy(cookieStoreFor = { NoOpCookieStore() }, userAgentFor = { "Pinned/1.0" })
        var deliveredHeaders: Map<String, String>? = null

        rule.setContent {
            HeaderPolicyTestShell(
                library = library,
                catalog = catalog,
                downloads = FakeDownloadManager(),
                headerPolicy = policy,
                onPageDelivered = { page -> deliveredHeaders = page.headers },
            )
        }
        rule.waitForIdle()

        rule.navigateVia("Browse")
        rule.onNodeWithText("Search…").performTextInput("solo")
        rule.waitForIdle()
        rule.onNodeWithText("solo").performImeAction()
        rule.waitForIdle()
        rule.onNodeWithText("Solo Leveling").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Start reading").performClick()
        rule.waitForIdle()

        assertEquals("Pinned/1.0", deliveredHeaders?.get("User-Agent"))
    }
}
