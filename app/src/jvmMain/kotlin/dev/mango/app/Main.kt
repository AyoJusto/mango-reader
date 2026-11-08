package dev.mango.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dev.mango.app.resources.Res
import dev.mango.app.resources.mango_icon
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.TitleBarPlacement
import dev.nucleusframework.window.WindowAppearance
import dev.nucleusframework.window.WindowAppearanceMode
import dev.nucleusframework.window.WindowBackground
import dev.nucleusframework.window.WindowControls
import dev.nucleusframework.window.WindowScaffold
import dev.nucleusframework.window.windowDragArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

fun main() {
    val graph = AppGraph()
    val settings = Settings(AppGraph.defaultDataDir())
    val themeStore = ThemeStore(AppGraph.defaultDataDir())
    // Must be set before the first composition asks SingletonImageLoader for a loader.
    SingletonImageLoader.setSafe { context -> ImageLoader.Builder(context).components { add(graph.imageFetcherFactory) }.build() }
    nucleusApplication(backend = NucleusBackend.Tao) {
        var theme by remember { mutableStateOf(themeStore.load()) }
        var themeLibrary by remember { mutableStateOf(themeStore.list()) }
        var autoScrollSpeed by remember { mutableStateOf(settings.autoScrollSpeed) }
        var stripWidthDp by remember { mutableStateOf(settings.stripWidth) }
        var hideCursorInReader by remember { mutableStateOf(settings.hideCursorInReader) }
        var libraryView by remember { mutableStateOf(settings.libraryView) }
        var fontFamilyName by remember { mutableStateOf(settings.fontFamilyName) }
        var libraryCheckedAt by remember { mutableStateOf(settings.libraryCheckedAt) }
        var searchHistory by remember { mutableStateOf(settings.searchHistory) }
        var installedFonts by remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(Unit) {
            installedFonts = withContext(Dispatchers.Default) { installedFontFamilies() }
        }
        var sidebarOpen by remember { mutableStateOf(false) }
        // Latch: a held S auto-repeats KeyDowns and each would re-toggle.
        var sidebarKeyDown by remember { mutableStateOf(false) }
        val windowState = rememberWindowState(size = DpSize(2560.dp, 1440.dp))
        val palette = remember { PaletteState() }
        val navigator = remember { Navigator() }
        val manageCollectionsOpen = remember { mutableStateOf(false) }
        val detector = remember { DoubleShiftDetector() }
        val lastInputWasKeyboard = remember { mutableStateOf(false) }
        DecoratedWindow(
            onCloseRequest = { graph.dispose(); exitApplication() },
            state = windowState,
            title = "mango",
            icon = painterResource(Res.drawable.mango_icon),
            onPreviewKeyEvent = { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) lastInputWasKeyboard.value = true
                when {
                    keyEvent.key == Key.S && keyEvent.isCtrlPressed && keyEvent.type == KeyEventType.KeyDown -> {
                        if (!sidebarKeyDown) {
                            sidebarKeyDown = true
                            sidebarOpen = !sidebarOpen
                        }
                        true
                    }
                    // Consume the S release only when the latch is armed; a plain S in a field passes through.
                    keyEvent.key == Key.S && keyEvent.type == KeyEventType.KeyUp && sidebarKeyDown -> {
                        sidebarKeyDown = false
                        true
                    }
                    // Feed downs and ups: the detector needs Shift releases to tell a double-tap from auto-repeat.
                    detector.onKeyEvent(keyEvent.key, keyEvent.type, System.currentTimeMillis()) -> {
                        palette.visible = !palette.visible
                        true
                    }

                    else -> false
                }
            },
            // Bubbling, not preview: a field or the reader wanting Esc consumes it first.
            onKeyEvent = { keyEvent ->
                if (keyEvent.key == Key.Escape && keyEvent.type == KeyEventType.KeyDown) {
                    backOrCloseOverlay(navigator, palette, manageCollectionsOpen)
                } else {
                    false
                }
            },
        ) {
            val appFont = remember(fontFamilyName, installedFonts) {
                resolveAppFontFamily(fontFamilyName, installedFonts)
            }
            val animationsOn = rememberWindowsAnimationsOn()
            val motion = remember(animationsOn) { MangoMotion(reduced = !animationsOn) }
            ProvideMangoTheme(theme, fontFamily = appFont) {
                WindowAppearance(WindowAppearanceMode.Dark)
                WindowBackground(LocalMangoTheme.current.bg0)
                CompositionLocalProvider(
                    LocalMangoMotion provides motion,
                    LocalLastInputWasKeyboard provides lastInputWasKeyboard,
                ) {
                    WindowScaffold(
                        titleBar = {
                            if (windowState.placement != WindowPlacement.Fullscreen) {
                                MangoTitleBar(
                                    sidebarOpen,
                                    { sidebarOpen = !sidebarOpen },
                                    // First in the chain so it sees presses the title-bar buttons consume.
                                    Modifier.trackLastInput(lastInputWasKeyboard).windowDragArea(),
                                    canGoBack = navigator.canGoBack,
                                    onBack = { navigator.back() },
                                    onMouseBack = {
                                        backOrCloseOverlay(navigator, palette, manageCollectionsOpen)
                                    },
                                ) {
                                    WindowControls(Modifier.align(Alignment.CenterEnd))
                                }
                            }
                        },
                        titleBarPlacement = TitleBarPlacement.Docked,
                    ) {
                        AppShell(
                            graph.library,
                            graph.catalog,
                            graph.downloads,
                            graph.extensions,
                            graph.challengeSolver,
                            graph.catalogCache,
                            headerPolicy = graph.headerPolicy,
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
                            navigator = navigator,
                            sidebarOpen = sidebarOpen,
                            onSidebarChange = { sidebarOpen = it },
                            libraryView = libraryView,
                            onLibraryViewChange = { libraryView = it; settings.libraryView = it },
                            fontFamilyName = fontFamilyName,
                            installedFonts = installedFonts,
                            onFontFamilyChange = { fontFamilyName = it; settings.fontFamilyName = it },
                            showTitleBar = false,
                            libraryCheckedAt = libraryCheckedAt,
                            onLibraryChecked = { libraryCheckedAt = it; settings.libraryCheckedAt = it },
                            searchHistory = searchHistory,
                            lastInputWasKeyboard = lastInputWasKeyboard,
                            manageCollectionsOpen = manageCollectionsOpen,
                            onSearchHistoryChange = { searchHistory = it; settings.searchHistory = it },
                        )
                    }
                }
            }
        }
    }
}
