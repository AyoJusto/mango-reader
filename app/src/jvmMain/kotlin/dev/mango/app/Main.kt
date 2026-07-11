package dev.mango.app

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
        val windowState = rememberWindowState()
        Window(onCloseRequest = ::exitApplication, title = "mango", state = windowState) {
            MangoTheme(themeName = settings.theme) {
                AppShell(
                    graph.library,
                    graph.catalog,
                    graph.downloads,
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
