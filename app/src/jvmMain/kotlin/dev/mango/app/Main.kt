package dev.mango.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() {
    val graph = AppGraph()
    application {
        val windowState = rememberWindowState()
        Window(onCloseRequest = ::exitApplication, title = "mango", state = windowState) {
            MangoTheme {
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
