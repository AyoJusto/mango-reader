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
        // opens maximized (screen-sized on any monitor); the DpSize is what un-maximizing
        // falls back to — 1440p-shaped so a floating window still reads comfortably
        val windowState = rememberWindowState(
            placement = WindowPlacement.Maximized,
            size = DpSize(2560.dp, 1440.dp),
        )
        Window(onCloseRequest = ::exitApplication, title = "mango", state = windowState) {
            MangoTheme(themeName = settings.theme) {
                AppShell(
                    graph.library,
                    graph.catalog,
                    graph.downloads,
                    onToggleFullscreen = {
                        // returns to Maximized (the default), not Floating — F out of
                        // fullscreen should not shrink the window
                        windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) {
                            WindowPlacement.Maximized
                        } else {
                            WindowPlacement.Fullscreen
                        }
                    },
                )
            }
        }
    }
}
