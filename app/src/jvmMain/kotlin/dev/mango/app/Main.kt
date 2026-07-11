package dev.mango.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() {
    val graph = AppGraph()
    // No settings page yet (M3.5c): edit <dataDir>/settings.properties by hand to change the
    // theme, e.g. `theme=midnight`. See Themes.schemes in Theme.kt for the available names.
    val settings = Settings(AppGraph.defaultDataDir())
    application {
        // default window size: 1440p (owner call, 2026-07-11) — a floating window this
        // size fills a 2560x1440 monitor; the OS clamps it on smaller screens
        val windowState = rememberWindowState(size = DpSize(2560.dp, 1440.dp))
        Window(
            onCloseRequest = { graph.dispose(); exitApplication() },
            title = "mango",
            state = windowState,
        ) {
            MangoTheme(themeName = settings.theme) {
                AppShell(
                    graph.library,
                    graph.catalog,
                    graph.downloads,
                    graph.extensions,
                    graph.challengeSolver,
                    onToggleFullscreen = {
                        windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) {
                            WindowPlacement.Floating
                        } else {
                            WindowPlacement.Fullscreen
                        }
                    },
                )
            }
        }
    }
}
