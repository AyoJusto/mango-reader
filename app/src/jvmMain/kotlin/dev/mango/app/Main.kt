package dev.mango.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() {
    val graph = AppGraph()
    // Theme is picked from the Settings screen (M4.4a); Settings.theme persists it to disk.
    val settings = Settings(AppGraph.defaultDataDir())
    application {
        // Hoisted into Compose state so picking a theme on the Settings screen applies live,
        // without restarting the app.
        var themeName by remember { mutableStateOf(settings.theme) }
        // default window size: 1440p (owner call, 2026-07-11) — a floating window this
        // size fills a 2560x1440 monitor; the OS clamps it on smaller screens
        val windowState = rememberWindowState(size = DpSize(2560.dp, 1440.dp))
        Window(
            onCloseRequest = { graph.dispose(); exitApplication() },
            title = "mango",
            state = windowState,
        ) {
            MangoTheme(themeName = themeName) {
                AppShell(
                    graph.library,
                    graph.catalog,
                    graph.downloads,
                    graph.extensions,
                    graph.challengeSolver,
                    currentTheme = themeName,
                    onThemeChange = { themeName = it; settings.theme = it },
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
