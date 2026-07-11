package dev.mango.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
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
        val palette = remember { PaletteState() }
        // Own file/class (M6a): a hand-rolled IntelliJ-style double-Shift chord detector —
        // this is Window-level, not a focusable Box inside AppShell, so it fires no matter
        // which screen or field currently has focus.
        val detector = remember { DoubleShiftDetector() }
        Window(
            onCloseRequest = { graph.dispose(); exitApplication() },
            title = "mango",
            state = windowState,
            onPreviewKeyEvent = { keyEvent ->
                // ALL key events go in, downs AND ups: the detector needs Shift releases to
                // tell a real double-tap from one held Shift auto-repeating keydowns
                if (detector.onKeyEvent(keyEvent.key, keyEvent.type, System.currentTimeMillis())) {
                    palette.visible = !palette.visible
                    true
                } else {
                    false
                }
            },
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
                    palette = palette,
                )
            }
        }
    }
}
