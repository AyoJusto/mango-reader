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

/** Used only when a caller doesn't wire a real registry (e.g. tests exercising other tabs). */
private object NoOpExtensionRepo : ExtensionRepo {
    override suspend fun available(): List<AvailableSource> = emptyList()
    override suspend fun install(source: AvailableSource) = Unit
}

/** Used only when a caller doesn't wire the real solver (tests exercising non-challenge flows). */
private object NoOpChallengeSolver : ChallengeSolver {
    override suspend fun solve(sourceId: String, url: String): Boolean = false
}

/** Used only when a caller doesn't wire the real cache (tests exercising other tabs): every read misses. */
private object NoOpCatalogCache : CatalogCache {
    override suspend fun get(sourceId: String, mangaId: String): CachedManga? = null
    override suspend fun put(sourceId: String, mangaId: String, details: MangaDetails, chapters: List<Chapter>) = Unit
}

/**
 * The whole application UI: the title bar on top (omitted when [showTitleBar] is false, because
 * the window draws it), the current screen filling the content area, an overlay sidebar for top-level navigation, and the command palette above everything.
 * Screens talk only to the repository ports passed in here — never engine or DB types
 * (CLAUDE.md boundary).
 */
@Composable
fun AppShell(
    library: LibraryRepository,
    catalog: CatalogRepository,
    downloads: DownloadManager,
    extensions: ExtensionRepo = NoOpExtensionRepo,
    challengeSolver: ChallengeSolver = NoOpChallengeSolver,
    catalogCache: CatalogCache = NoOpCatalogCache,
    // Optional hook, same style as the other repository ports above: production wires
    // AppGraph.headerPolicy, tests leave it null.
    headerPolicy: SourceHeaderPolicy? = null,
    // Optional hook, same style as ReaderScreen's own pageContent: lets tests intercept pages
    // delivered to the Reader branch without hitting the network. Production leaves it null.
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
    // Owned by the caller so Esc and mouse back over the title bar, which production draws
    // outside this function, can close the manage-collections dialog.
    manageCollectionsOpen: MutableState<Boolean> = remember { mutableStateOf(false) },
    // Owned by the caller so the title bar, which production draws outside this function, shares it.
    lastInputWasKeyboard: MutableState<Boolean> = remember { mutableStateOf(false) },
) {
    // Session-only, like searchState below: which collection the Library chip row is filtering
    // to, and whether the manage-collections dialog is open.
    var selectedCollectionId by remember { mutableStateOf<Long?>(null) }
    var showManageCollections by manageCollectionsOpen
    // Hoisted here (not inside BrowseScreen) so it survives tab switches: Library -> Browse ->
    // Library -> Browse must show the previous query/results instead of resetting.
    val browseState = remember { BrowseState() }
    // Same rationale as browseState: Search's query/results/enabled-sources must survive
    // switching to another tab and back.
    val searchState = remember { SearchState() }
    val scope = rememberCoroutineScope()

    val motion = LocalMangoMotion.current
    val updater = remember(catalog, catalogCache, library) { LibraryUpdater(catalog, catalogCache, library) }
    var checking by remember { mutableStateOf(false) }

    // Single flight: a second click while a check is running is a no-op. The checked-at stamp
    // only advances after a full run completes, so a cancelled run leaves it untouched.
    fun checkForUpdates() {
        if (checking) return
        // Set before launch: the launched body runs on a later event-loop turn, so flipping the
        // flag inside it would let a second click pass the guard first.
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

    // Continue cards are loaded once per sidebar open — the panel is transient, so a snapshot
    // is enough; no need to observe progress while it is closed.
    var continueItems by remember { mutableStateOf<List<ContinueItem>>(emptyList()) }
    LaunchedEffect(sidebarOpen) {
        if (!sidebarOpen) return@LaunchedEffect
        val items = library.observeLibrary().first()
        // ponytail: per-item latestProgress queries, one bulk query if libraries outgrow double digits
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
    // Collected here (not just inside LibraryScreen) so the palette's collection hits stay
    // in sync with the same data the chip row shows.
    val collections by library.observeCollections().collectAsState(initial = emptyList())
    // Collected again here (LibraryScreen already collects its own copy) so the manage-collections
    // dialog's member counts stay live while it's open.
    val libraryItems by library.observeLibrary().collectAsState(initial = emptyList())
    val collectionMemberCounts = remember(libraryItems) {
        libraryItems.flatMap { it.collectionIds }.groupingBy { it }.eachCount()
    }
    // Pending = still going to happen on its own; FAILED needs a user retry, not a badge.
    val pendingDownloadCount = downloadRows.count {
        it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING
    }
    // The sidebar's frosted panel blurs whatever screen content is behind it; the screen
    // container below registers as the blur source, the panel samples it.
    val hazeState = rememberHazeState()
    val density = LocalDensity.current
    val slidePx = with(density) { motion.screenSlide.roundToPx() }
    val risePx = with(density) { motion.viewRise.roundToPx() }
    // Stacked entries keep their scroll and other saveable state while covered; popped and
    // cleared entries drop theirs.
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
        // Content, sidebar, and palette live in one Box so the overlays can stack above
        // whichever screen is showing; the title bar stays visible over all of them.
        Box(modifier = Modifier.weight(1f).fillMaxWidth().sideButtons { mouseBack() }) {
            CompositionLocalProvider(LocalSourceHeaderPolicy provides headerPolicy) {
                // The content behind the palette blurs while it's open — the haze sidebar source
                // modifier below stays untouched; this composes an ordinary blur onto the same Box.
                val contentBlurDp by animateDpAsState(
                    targetValue = if (palette.visible) 10.dp else 0.dp,
                    animationSpec = tween(motion.paletteBackdropMs),
                )
                val contentBlur = if (contentBlurDp > 0.dp) Modifier.blur(contentBlurDp) else Modifier
                SharedTransitionLayout(modifier = Modifier.fillMaxSize().hazeSource(hazeState).then(contentBlur)) {
                    val sharedTransitionScope = this
                    AnimatedContent(
                        targetState = navigator.current,
                        // The id, not the whole entry: replaceCurrent swaps the screen in place and must not animate.
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
                                                            // The one-shot flag comes off the stack entry first,
                                                            // so backing out of the Reader lands on plain Details.
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
                                                        // Downloading a chapter (or the whole series) implies the user
                                                        // cares about it: it lands in the library too, same as a manual
                                                        // "Add to library" tap.
                                                        onDownloadChapter = { entry, chapter ->
                                                            scope.launch {
                                                                library.addToLibrary(entry)
                                                                downloads.enqueue(entry, chapter)
                                                                downloads.processQueue()
                                                            }
                                                        },
                                                        onDownloadAll = { entry, chapters ->
                                                            // empty selection ("unread" with everything read) must
                                                            // not side-effect the library or spin the queue
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
                // The palette's toggle-sidebar/toggle-library-view hits read the CURRENT state at run
                // time, not the value captured when the tab list was remembered below.
                val currentSidebarOpen by rememberUpdatedState(sidebarOpen)
                val currentLibraryView by rememberUpdatedState(libraryView)
                // keyed on theme and themeLibrary (not plain remember{}): the accent and theme
                // providers close over the current theme and library by value, so either changing
                // must rebuild the tab list or its hits would apply a stale theme, or one already
                // deleted from the library
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

/**
 * What Esc and the mouse back button do: close the top-most open overlay, or go back when none
 * is open. False when there was nothing to close and nowhere to go back to.
 */
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

/**
 * The [AnimatedVisibilityScope] of the screen transition, for screen content that animates
 * along with it; null outside the screen host.
 */
val LocalAnimatedVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** The [SharedTransitionScope] screens share elements through; null outside the screen host. */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/**
 * Pushes slide in from the right, pops from the left, and top-level switches rise in place.
 * Entering or leaving the [Screen.Reader] only fades, so the reader keeps its immersive entry.
 */
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

/** Drops the saved state of every entry that has left the back stack. */
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

