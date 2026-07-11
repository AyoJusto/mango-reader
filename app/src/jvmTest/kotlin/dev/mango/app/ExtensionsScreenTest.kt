package dev.mango.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.mango.core.domain.AvailableSource
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Review-artifact screenshot plus an install flow for the M4.2 Extensions screen, backed by
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
}
