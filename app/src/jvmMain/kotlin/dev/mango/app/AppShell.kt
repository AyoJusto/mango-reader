package dev.mango.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.mango.core.domain.AvailableSource
import dev.mango.core.domain.CachedManga
import dev.mango.core.domain.CatalogCache
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.ChallengeSolver
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.DownloadManager
import dev.mango.core.domain.DownloadStatus
import dev.mango.core.domain.ExtensionRepo
import dev.mango.core.domain.LibraryRepository
import dev.mango.core.domain.LibraryUpdater
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.Page
import dev.mango.core.domain.SourceHeaderPolicy
import kotlin.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private object NoOpExtensionRepo : ExtensionRepo {
    override suspend fun available(): List<AvailableSource> = emptyList()
    override suspend fun install(source: AvailableSource) = Unit
}

private object NoOpChallengeSolver : ChallengeSolver {
    override suspend fun solve(sourceId: String, url: String): Boolean = false
}

private object NoOpCatalogCache : CatalogCache {
    override suspend fun get(sourceId: String, mangaId: String): CachedManga? = null
    override suspend fun put(sourceId: String, mangaId: String, details: MangaDetails, chapters: List<Chapter>) = Unit
}

@Composable
fun AppShell(
    library: LibraryRepository,
    catalog: CatalogRepository,
    downloads: DownloadManager,
    extensions: ExtensionRepo = NoOpExtensionRepo,
    challengeSolver: ChallengeSolver = NoOpChallengeSolver,
    catalogCache: CatalogCache = NoOpCatalogCache,
    headerPolicy: SourceHeaderPolicy? = null,
    readerPageContent: (@Composable (Page) -> Unit)? = null,
    theme: MangoTheme = MangoDark,
    onThemeChange: (MangoTheme) -> Unit = {},
    themeLibrary: List<MangoTheme> = listOf(MangoDark),
    onThemeImport: (MangoTheme) -> String? = { null },
    onThemeDelete: () -> Unit = {},
    autoScrollSpeed: Float = 120f,
    onAutoScrollSpeedChange: (Float) -> Unit = {},
    stripWidthDp: Float = 880f,
    onStripWidthDpChange: (Float) -> Unit = {},
    hideCursorInReader: Boolean = true,
    onHideCursorInReaderChange: (Boolean) -> Unit = {},
    onToggleFullscreen: () -> Unit = {},
    palette: PaletteState = remember { PaletteState() },
    sidebarOpen: Boolean = false,
    onSidebarChange: (Boolean) -> Unit = {},
    libraryView: String = LIBRARY_VIEW_GRID,
    onLibraryViewChange: (String) -> Unit = {},
    fontFamilyName: String? = null,
    installedFonts: List<String> = emptyList(),
    onFontFamilyChange: (String?) -> Unit = {},
    showTitleBar: Boolean = true,
    libraryCheckedAt: Long? = null,
    onLibraryChecked: (Long) -> Unit = {},
    searchHistory: List<SearchHistoryEntry> = emptyList(),
    onSearchHistoryChange: (List<SearchHistoryEntry>) -> Unit = {},
    navigator: Navigator = remember { Navigator() },
    // Owned by the caller because production draws the title bar outside this function.
    manageCollectionsOpen: MutableState<Boolean> = remember { mutableStateOf(false) },
    lastInputWasKeyboard: MutableState<Boolean> = remember { mutableStateOf(false) },
) {
    var selectedCollectionId by remember { mutableStateOf<Long?>(null) }
    var showManageCollections by manageCollectionsOpen
    val browseState = remember { BrowseState() }
    val searchState = remember { SearchState() }
    val scope = rememberCoroutineScope()

    val motion = LocalMangoMotion.current
    val updater = remember(catalog, catalogCache, library) { LibraryUpdater(catalog, catalogCache, library) }
    var checking by remember { mutableStateOf(false) }

    // Single flight; the checked-at stamp only advances after a full run completes.
    fun checkForUpdates() {
        if (checking) return
        // Set before launch so a second click can't pass the guard first.
        checking = true
        scope.launch {
            try {
                updater.checkAll()
            } finally {
                checking = false
            }
            onLibraryChecked(Clock.System.now().toEpochMilliseconds())
        }
    }

    var continueItems by remember { mutableStateOf<List<ContinueItem>>(emptyList()) }
    LaunchedEffect(sidebarOpen) {
        if (!sidebarOpen) return@LaunchedEffect
        val items = library.observeLibrary().first()
        continueItems = items
            .mapNotNull { item ->
                library.latestProgress(item.entry.sourceId, item.entry.mangaId)?.let { item to it }
            }
            .sortedByDescending { (_, progress) -> progress.updatedAt }
            .take(3)
            .map { (item, progress) ->
                ContinueItem(
                    sourceId = item.entry.sourceId,
                    mangaId = item.entry.mangaId,
                    title = item.entry.title,
                    cover = item.entry.cover,
                    progressLine = if (progress.chapterNumber > 0) {
                        "Ch. ${formatChapterNumber(progress.chapterNumber)}"
                    } else {
                        "p. ${progress.page + 1}"
                    },
                )
            }
    }
    val downloadRows by downloads.observeDownloads().collectAsState(initial = emptyList())
    val collections by library.observeCollections().collectAsState(initial = emptyList())
    val libraryItems by library.observeLibrary().collectAsState(initial = emptyList())
    val collectionMemberCounts = remember(libraryItems) {
        libraryItems.flatMap { it.collectionIds }.groupingBy { it }.eachCount()
    }
    val pendingDownloadCount = downloadRows.count {
        it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING
    }
    val hazeState = rememberHazeState()
    val density = LocalDensity.current
    val slidePx = with(density) { motion.screenSlide.roundToPx() }
    val risePx = with(density) { motion.viewRise.roundToPx() }
    val screenStates = rememberSaveableStateHolder()
    ForgetPoppedState(navigator, screenStates)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .trackLastInput(lastInputWasKeyboard),
    ) {
        val mouseBack = { backOrCloseOverlay(navigator, palette, sidebarOpen, onSidebarChange, manageCollectionsOpen) }
        if (showTitleBar) {
            MangoTitleBar(
                sidebarOpen = sidebarOpen,
                onToggleSidebar = { onSidebarChange(!sidebarOpen) },
                canGoBack = navigator.canGoBack,
                onBack = { navigator.back() },
                onMouseBack = { mouseBack() },
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth().sideButtons { mouseBack() }) {
            CompositionLocalProvider(LocalSourceHeaderPolicy provides headerPolicy) {
                val contentBlurDp by animateDpAsState(
                    targetValue = if (palette.visible) 10.dp else 0.dp,
                    animationSpec = tween(motion.paletteBackdropMs),
                )
                val contentBlur = if (contentBlurDp > 0.dp) Modifier.blur(contentBlurDp) else Modifier
                SharedTransitionLayout(modifier = Modifier.fillMaxSize().hazeSource(hazeState).then(contentBlur)) {
                    val sharedTransitionScope = this
                    AnimatedContent(
                        targetState = navigator.current,
                        // The id, not the whole entry: replaceCurrent swaps in place and must not animate.
                        contentKey = { it.id },
                        transitionSpec = {
                            val reader = initialState.screen is Screen.Reader || targetState.screen is Screen.Reader
                            screenTransition(navigator.lastMove, reader, motion, slidePx, risePx)
                        },
                        label = "screen",
                    ) { entry ->
                        val current = entry.screen
                        val rootModifier = Modifier.fillMaxSize().testTag("screen:${current::class.simpleName}")
                        CompositionLocalProvider(
                            LocalAnimatedVisibilityScope provides this,
                            LocalSharedTransitionScope provides sharedTransitionScope,
                        ) {
                            screenStates.SaveableStateProvider(entry.id) {
                                if (current is Screen.Reader) {
                                    Box(modifier = rootModifier) {
                                        ReaderScreen(
                                            sourceId = current.sourceId,
                                            mangaId = current.mangaId,
                                            chapterId = current.chapterId,
                                            chapters = current.chapters,
                                            catalog = catalog,
                                            downloads = downloads,
                                            library = library,
                                            challengeSolver = challengeSolver,
                                            onBack = { navigator.back() },
                                            onToggleFullscreen = onToggleFullscreen,
                                            autoScrollSpeedDpPerSec = autoScrollSpeed,
                                            stripWidthDp = stripWidthDp,
                                            paletteVisible = palette.visible,
                                            hideCursorInReader = hideCursorInReader,
                                            headerPolicy = headerPolicy,
                                            pageContent = readerPageContent,
                                        )
                                    }
                                } else {
                                    Surface(modifier = rootModifier, color = theme.bg0) {
                                        when (current) {
                                            Screen.Library -> LibraryScreen(
                                                library = library,
                                                libraryView = libraryView,
                                                onLibraryViewChange = onLibraryViewChange,
                                                onBrowse = { navigator.go(Screen.Browse) },
                                                onOpenDetails = { entry ->
                                                    navigator.go(Screen.Details(entry.sourceId, entry.mangaId))
                                                },
                                                checkedAt = libraryCheckedAt,
                                                checking = checking,
                                                onCheckForUpdates = { checkForUpdates() },
                                                selectedCollectionId = selectedCollectionId,
                                                onSelectCollection = { selectedCollectionId = it },
                                                onManageCollections = { showManageCollections = true },
                                            )

                                            Screen.Search -> SearchScreen(
                                                catalog = catalog,
                                                challengeSolver = challengeSolver,
                                                state = searchState,
                                                onOpenDetails = { entry ->
                                                    navigator.go(Screen.Details(entry.sourceId, entry.mangaId))
                                                },
                                                searchHistory = searchHistory,
                                                onSearchHistoryChange = onSearchHistoryChange,
                                            )

                                            Screen.Browse -> BrowseScreen(catalog, challengeSolver, browseState) { entry ->
                                                navigator.go(Screen.Details(entry.sourceId, entry.mangaId))
                                            }

                                            Screen.Downloads -> DownloadsScreen(downloads)
                                            Screen.Extensions -> ExtensionsScreen(extensions, catalog)
                                            Screen.Settings -> SettingsScreenContent(
                                                theme = theme,
                                                onThemeChange = onThemeChange,
                                                themeLibrary = themeLibrary,
                                                onThemeImport = onThemeImport,
                                                onThemeDelete = onThemeDelete,
                                                autoScrollSpeed = autoScrollSpeed,
                                                onAutoScrollSpeedChange = onAutoScrollSpeedChange,
                                                stripWidth = stripWidthDp,
                                                onStripWidthChange = onStripWidthDpChange,
                                                hideCursorInReader = hideCursorInReader,
                                                onHideCursorInReaderChange = onHideCursorInReaderChange,
                                                libraryView = libraryView,
                                                onLibraryViewChange = onLibraryViewChange,
                                                fontFamilyName = fontFamilyName,
                                                installedFonts = installedFonts,
                                                onFontFamilyChange = onFontFamilyChange,
                                            )

                                            is Screen.Details -> {
                                                Box(modifier = Modifier.fillMaxSize()) {
                                                    DetailsScreen(
                                                        sourceId = current.sourceId,
                                                        mangaId = current.mangaId,
                                                        catalog = catalog,
                                                        library = library,
                                                        downloads = downloads,
                                                        challengeSolver = challengeSolver,
                                                        catalogCache = catalogCache,
                                                        autoContinue = current.autoContinue,
                                                        onOpenChapter = { chapter, chapters ->
                                                            // Clear the one-shot flag first so backing out of the Reader lands on plain Details.
                                                            navigator.replaceCurrent(current.copy(autoContinue = false))
                                                            navigator.go(
                                                                Screen.Reader(
                                                                    sourceId = current.sourceId,
                                                                    mangaId = current.mangaId,
                                                                    chapterId = chapter.chapterId,
                                                                    chapters = chapters.sortedBy { it.number },
                                                                ),
                                                            )
                                                        },
                                                        onDownloadChapter = { entry, chapter ->
                                                            scope.launch {
                                                                library.addToLibrary(entry)
                                                                downloads.enqueue(entry, chapter)
                                                                downloads.processQueue()
                                                            }
                                                        },
                                                        onDownloadAll = { entry, chapters ->
                                                            // An empty selection must not touch the library or spin the queue.
                                                            if (chapters.isNotEmpty()) {
                                                                scope.launch {
                                                                    library.addToLibrary(entry)
                                                                    chapters.forEach { downloads.enqueue(entry, it) }
                                                                    downloads.processQueue()
                                                                }
                                                            }
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Sidebar(
                    visible = sidebarOpen,
                    continueItems = continueItems,
                    activeScreen = navigator.current.screen,
                    pendingDownloadCount = pendingDownloadCount,
                    onNavigate = { target ->
                        navigator.go(target)
                        onSidebarChange(false)
                    },
                    onContinue = { item ->
                        navigator.go(Screen.Details(item.sourceId, item.mangaId, autoContinue = true))
                        onSidebarChange(false)
                    },
                    modifier = Modifier.align(Alignment.CenterStart),
                    hazeState = hazeState,
                )
                // Read current state at run time, not the value captured when the tab list was remembered.
                val currentSidebarOpen by rememberUpdatedState(sidebarOpen)
                val currentLibraryView by rememberUpdatedState(libraryView)
                // Keyed on theme and themeLibrary: providers close over them by value and would apply stale ones.
                val tabs = remember(theme, themeLibrary, collections) {
                    paletteTabs(
                        library = library,
                        navigate = { target -> navigator.go(target) },
                        theme = theme,
                        onThemeChange = onThemeChange,
                        themes = themeLibrary,
                        onToggleSidebar = { onSidebarChange(!currentSidebarOpen) },
                        onToggleLibraryView = {
                            onLibraryViewChange(if (currentLibraryView == LIBRARY_VIEW_LIST) LIBRARY_VIEW_GRID else LIBRARY_VIEW_LIST)
                        },
                        onCheckForUpdates = { checkForUpdates() },
                        onClearSearchHistory = { onSearchHistoryChange(emptyList()) },
                        collections = collections,
                        onSelectCollection = { id -> selectedCollectionId = id; navigator.go(Screen.Library) },
                        onManageCollections = { showManageCollections = true },
                    )
                }
                PaletteOverlay(state = palette, tabs = tabs)
                if (showManageCollections) {
                    ManageCollectionsDialog(
                        collections = collections,
                        memberCounts = collectionMemberCounts,
                        onRename = { id, name -> library.renameCollection(id, name) },
                        onDelete = { id -> scope.launch { library.deleteCollection(id) } },
                        onReorder = { ids -> scope.launch { library.reorderCollections(ids) } },
                        onSetDefault = { id -> scope.launch { library.setDefaultCollection(id) } },
                        onCreate = { name -> library.createCollection(name) },
                        onDismiss = { showManageCollections = false },
                    )
                }
            }
        }
    }
}

/** False when there was nothing to close and nowhere to go back to. */
fun backOrCloseOverlay(
    navigator: Navigator,
    palette: PaletteState,
    sidebarOpen: Boolean,
    onSidebarChange: (Boolean) -> Unit,
    manageCollectionsOpen: MutableState<Boolean>,
): Boolean {
    when {
        manageCollectionsOpen.value -> manageCollectionsOpen.value = false
        palette.visible -> palette.visible = false
        sidebarOpen -> onSidebarChange(false)
        else -> return navigator.back()
    }
    return true
}

val LocalAnimatedVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

private fun screenTransition(
    move: Move,
    involvesReader: Boolean,
    motion: MangoMotion,
    slidePx: Int,
    risePx: Int,
): ContentTransform {
    if (motion.reduced || involvesReader) {
        return fadeIn(tween(motion.screenMs)) togetherWith fadeOut(tween(motion.screenMs))
    }
    return when (move) {
        Move.Push -> (slideInHorizontally(tween(motion.screenMs, easing = motion.decel)) { +slidePx } +
            fadeIn(tween(motion.screenMs))) togetherWith fadeOut(tween(motion.screenMs))

        Move.Pop -> (slideInHorizontally(tween(motion.screenMs, easing = motion.decel)) { -slidePx } +
            fadeIn(tween(motion.screenMs))) togetherWith fadeOut(tween(motion.screenMs))

        Move.Switch -> (fadeIn(tween(motion.viewChangeMs, easing = motion.decel)) +
            slideInVertically(tween(motion.viewChangeMs, easing = motion.decel)) { +risePx }) togetherWith
            fadeOut(tween(motion.viewChangeMs))
    }
}

@Composable
private fun ForgetPoppedState(navigator: Navigator, stateHolder: SaveableStateHolder) {
    val liveIds = navigator.liveIds
    val known = remember { mutableSetOf<Long>() }
    SideEffect {
        (known - liveIds.toSet()).forEach(stateHolder::removeState)
        known.clear()
        known.addAll(liveIds)
    }
}

