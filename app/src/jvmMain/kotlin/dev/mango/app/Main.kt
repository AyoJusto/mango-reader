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
    val settings = Settings(AppGraph.defaultDataDir())
    // Theme is picked from the Settings screen (or the palette's accent hits); ThemeStore
    // persists it to disk on every change.
    val themeStore = ThemeStore(AppGraph.defaultDataDir())
    application {
        // Hoisted into Compose state so picking a theme on the Settings screen applies live,
        // without restarting the app.
        var theme by remember { mutableStateOf(themeStore.load()) }
        // Same hoist pattern as theme: the Settings screen's slider applies live, without
        // restarting the app.
        var autoScrollSpeed by remember { mutableStateOf(settings.autoScrollSpeed) }
        // A floating window this size fills a 2560x1440 monitor; the OS clamps it on smaller screens
        val windowState = rememberWindowState(size = DpSize(2560.dp, 1440.dp))
        val palette = remember { PaletteState() }
        // A hand-rolled IntelliJ-style double-Shift chord detector, own file/class — this is
        // Window-level, not a focusable Box inside AppShell, so it fires no matter which
        // screen or field currently has focus.
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
            ProvideMangoTheme(theme) {
                AppShell(
                    graph.library,
                    graph.catalog,
                    graph.downloads,
                    graph.extensions,
                    graph.challengeSolver,
                    theme = theme,
                    onThemeChange = { theme = it; themeStore.save(it) },
                    autoScrollSpeed = autoScrollSpeed,
                    onAutoScrollSpeedChange = { autoScrollSpeed = it; settings.autoScrollSpeed = it },
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
