package dev.mango.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.mango.core.domain.AvailableSource
import dev.mango.core.domain.SourceInfo
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot plus an install flow for the Extensions screen, backed by
 * [FakeExtensionRepo]/[FakeCatalogRepository]. Style mirrors DownloadsTest/ScreenFlowTest.
 */
class ExtensionsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun extensionsScreenshot() {
        val available = listOf(
            AvailableSource(sourceId = "FlameComics", name = "Flame Comics", version = "1.0.0", language = "en"),
            AvailableSource(sourceId = "MangaBat", name = "MangaBat", version = "2.0.0", language = "en"),
            AvailableSource(sourceId = "Toonily", name = "Toonily", version = "3.0.0", language = "en"),
            AvailableSource(sourceId = "WebtoonXYZ", name = "WebtoonXYZ", version = "1.5.0", language = "en"),
        )
        // FlameComics: not installed -> "Install"
        // MangaBat: installed at the current version -> "Installed"
        // Toonily: installed at an older version -> "Update to 3.0.0"
        // WebtoonXYZ: install in flight -> spinner
        val installed = mapOf("MangaBat" to "2.0.0", "Toonily" to "2.9.0")
        val busy = setOf("WebtoonXYZ")

        val file = Screenshots.render("extensions") {
            MangoTheme {
                ExtensionsScreenContent(
                    available = available,
                    installed = installed,
                    busy = busy,
                    isLoading = false,
                    error = null,
                    onInstall = {},
                    onRemove = {},
                )
            }
        }
        assertTrue(Files.size(file) > 0, "expected a non-empty PNG at $file")
    }

    @Test
    fun clickingInstallCallsTheRepoAndTheRowFlipsToInstalledAfterRefresh() {
        val source = AvailableSource(sourceId = "FlameComics", name = "Flame Comics", version = "1.0.0", language = "en")
        val catalog = FakeCatalogRepository()
        val repo = FakeExtensionRepo(available = listOf(source), catalog = catalog)

        rule.setContent { MangoTheme { ExtensionsScreen(repo, catalog) } }
        rule.waitForIdle()

        rule.onNodeWithText("Install").assertExists()

        rule.onNodeWithText("Install").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Installed").assertExists()
        assertEquals(1, repo.installCallCount)
    }

    @Test
    fun installedRowShowsRemoveAndNotInstalledRowDoesNot() {
        val installedSource = AvailableSource(sourceId = "FlameComics", name = "Flame Comics", version = "1.0.0", language = "en")
        val notInstalledSource = AvailableSource(sourceId = "MangaBat", name = "MangaBat", version = "2.0.0", language = "en")

        rule.setContent {
            MangoTheme {
                ExtensionsScreenContent(
                    available = listOf(installedSource, notInstalledSource),
                    installed = mapOf("FlameComics" to "1.0.0"),
                    busy = emptySet(),
                    isLoading = false,
                    error = null,
                    onInstall = {},
                    onRemove = {},
                )
            }
        }

        rule.onNodeWithText("Install").assertExists()
        rule.onAllNodesWithText("Remove").assertCountEquals(1)
    }

    @Test
    fun clickingRemoveCallsUninstallAndTheRowFlipsToInstallAfterRefresh() {
        val source = AvailableSource(sourceId = "FlameComics", name = "Flame Comics", version = "1.0.0", language = "en")
        val catalog = FakeCatalogRepository(sources = listOf(SourceInfo(sourceId = "FlameComics", name = "Flame Comics", version = "1.0.0")))
        val repo = FakeExtensionRepo(available = listOf(source), catalog = catalog)

        rule.setContent { MangoTheme { ExtensionsScreen(repo, catalog) } }
        rule.waitForIdle()

        rule.onNodeWithText("Remove").assertExists()

        rule.onNodeWithText("Remove").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Install").assertExists()
        assertEquals(emptyList(), runBlocking { catalog.installedSources() })
    }
}
