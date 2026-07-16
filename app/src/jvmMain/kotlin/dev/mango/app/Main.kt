package dev.mango.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.mango.app.resources.Res
import dev.mango.app.resources.mango_icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

fun main() {
    val graph = AppGraph()
    val settings = Settings(AppGraph.defaultDataDir())
    val themeStore = ThemeStore(AppGraph.defaultDataDir())
    application {
        var theme by remember { mutableStateOf(themeStore.load()) }
        var themeLibrary by remember { mutableStateOf(themeStore.list()) }
        var autoScrollSpeed by remember { mutableStateOf(settings.autoScrollSpeed) }
        var stripWidthDp by remember { mutableStateOf(settings.stripWidth) }
        var hideCursorInReader by remember { mutableStateOf(settings.hideCursorInReader) }
        var libraryView by remember { mutableStateOf(settings.libraryView) }
        var fontFamilyName by remember { mutableStateOf(settings.fontFamilyName) }
        var libraryCheckedAt by remember { mutableStateOf(settings.libraryCheckedAt) }
        var searchHistory by remember { mutableStateOf(settings.searchHistory) }
        // Enumerating a few hundred system font families isn't free; do it once, off the UI
        // thread. The dropdown shows only "System default" until the list lands.
        var installedFonts by remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(Unit) {
            installedFonts = withContext(Dispatchers.Default) { installedFontFamilies() }
        }
        var sidebarOpen by remember { mutableStateOf(false) }
        // Latch for the Ctrl+S toggle: a held key auto-repeats KeyDowns, and each one would
        // re-toggle without it. Set on the first S down, cleared on S up.
        var sidebarKeyDown by remember { mutableStateOf(false) }
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
            icon = painterResource(Res.drawable.mango_icon),
            state = windowState,
            onPreviewKeyEvent = { keyEvent ->
                when {
                    keyEvent.key == Key.S && keyEvent.isCtrlPressed && keyEvent.type == KeyEventType.KeyDown -> {
                        if (!sidebarKeyDown) {
                            sidebarKeyDown = true
                            sidebarOpen = !sidebarOpen
                        }
                        true
                    }
                    // Consume the matching S release only when the latch is armed; a plain S
                    // (typing in a field) passes through untouched.
                    keyEvent.key == Key.S && keyEvent.type == KeyEventType.KeyUp && sidebarKeyDown -> {
                        sidebarKeyDown = false
                        true
                    }
                    // Esc closes the sidebar only when nothing above it is open; the palette
                    // and the reader own their Esc semantics.
                    keyEvent.key == Key.Escape && keyEvent.type == KeyEventType.KeyDown &&
                            sidebarOpen && !palette.visible -> {
                        sidebarOpen = false
                        true
                    }
                    // ALL remaining key events go in, downs AND ups: the detector needs Shift
                    // releases to tell a real double-tap from one held Shift auto-repeating keydowns
                    detector.onKeyEvent(keyEvent.key, keyEvent.type, System.currentTimeMillis()) -> {
                        palette.visible = !palette.visible
                        true
                    }

                    else -> false
                }
            },
        ) {
            // The native bar's height parameter is physical pixels; convert with the window's
            // own density so the merged bar lines up on any display scale.
            val density = LocalDensity.current
            var jbrBar by remember { mutableStateOf<JbrBar?>(null) }
            LaunchedEffect(Unit) {
                jbrBar = applyJbrTitleBar(window, with(density) { TITLE_BAR_HEIGHT.toPx() })
            }
            val appFont = remember(fontFamilyName, installedFonts) {
                resolveAppFontFamily(fontFamilyName, installedFonts)
            }
            ProvideMangoTheme(theme, fontFamily = appFont) {
                AppShell(
                    graph.library,
                    graph.catalog,
                    graph.downloads,
                    graph.extensions,
                    graph.challengeSolver,
                    graph.catalogCache,
                    theme = theme,
                    onThemeChange = { theme = it; themeStore.save(it) },
                    themeLibrary = themeLibrary,
                    onThemeImport = { imported ->
                        val err = themeStore.saveImported(imported)
                        if (err == null) {
                            themeLibrary = themeStore.list()
                            theme = imported
                            themeStore.save(imported)
                        }
                        err
                    },
                    onThemeDelete = {
                        themeStore.deleteImported(theme.name)
                        themeLibrary = themeStore.list()
                        theme = MangoDark
                        themeStore.save(MangoDark)
                    },
                    autoScrollSpeed = autoScrollSpeed,
                    onAutoScrollSpeedChange = { autoScrollSpeed = it; settings.autoScrollSpeed = it },
                    stripWidthDp = stripWidthDp,
                    onStripWidthDpChange = { stripWidthDp = it; settings.stripWidth = it },
                    hideCursorInReader = hideCursorInReader,
                    onHideCursorInReaderChange = { hideCursorInReader = it; settings.hideCursorInReader = it },
                    onToggleFullscreen = {
                        windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) {
                            WindowPlacement.Floating
                        } else {
                            WindowPlacement.Fullscreen
                        }
                    },
                    palette = palette,
                    sidebarOpen = sidebarOpen,
                    onSidebarChange = { sidebarOpen = it },
                    libraryView = libraryView,
                    onLibraryViewChange = { libraryView = it; settings.libraryView = it },
                    fontFamilyName = fontFamilyName,
                    installedFonts = installedFonts,
                    onFontFamilyChange = { fontFamilyName = it; settings.fontFamilyName = it },
                    jbrBar = jbrBar,
                    libraryCheckedAt = libraryCheckedAt,
                    onLibraryChecked = { libraryCheckedAt = it; settings.libraryCheckedAt = it },
                    searchHistory = searchHistory,
                    onSearchHistoryChange = { searchHistory = it; settings.searchHistory = it },
                )
            }
        }
    }
}
