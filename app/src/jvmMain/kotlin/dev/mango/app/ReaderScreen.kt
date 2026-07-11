package dev.mango.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.LibraryRepository
import dev.mango.core.domain.Page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** How long the strip scrolls forward/back per Space/PageDown/PageUp keypress, as a fraction of the viewport. */
private const val PAGE_SCROLL_FRACTION = 0.9f

/** How long the controls overlay stays up after the last pointer move, in milliseconds. */
private const val CONTROLS_AUTO_HIDE_MS = 2000L

/**
 * Pure, data-driven long-strip content — the screenshot harness renders this directly.
 * Full-bleed pages in a [LazyColumn] on [ReaderBlack], centered at a max width so ultrawide
 * monitors don't stretch pages absurdly, with a fading controls overlay on top.
 */
@Composable
fun ReaderContent(
    title: String,
    pages: List<Page>,
    listState: LazyListState,
    controlsVisible: Boolean,
    onBack: () -> Unit,
    pageContent: @Composable (Page) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = ReaderBlack) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxHeight()
                    .widthIn(max = 1000.dp)
                    .fillMaxWidth(),
            ) {
                items(pages, key = { it.index }) { page -> pageContent(page) }
            }
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            ) {
                Surface(color = MaterialTheme.colorScheme.background.copy(alpha = 0.85f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Text("←", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(modifier = Modifier.fillMaxWidth().weight(1f))
                        Text(
                            text = "${listState.firstVisibleItemIndex + 1} / ${pages.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Stateful loader: loads this chapter's pages once, restores saved progress, and owns all
 * reader input (keyboard paging, fullscreen toggle, auto-hiding controls). Persists progress
 * with a short debounce so scroll-driven recomposition doesn't hammer [library].
 */
@OptIn(FlowPreview::class, ExperimentalComposeUiApi::class)
@Composable
fun ReaderScreen(
    sourceId: String,
    mangaId: String,
    chapterId: String,
    chapterLabel: String,
    catalog: CatalogRepository,
    library: LibraryRepository,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    progressDebounceMillis: Long = 500,
    pageContent: @Composable (Page) -> Unit = { page -> DefaultReaderPage(page) },
) {
    var pages by remember(sourceId, mangaId, chapterId) { mutableStateOf<List<Page>?>(null) }
    var savedPage by remember(sourceId, mangaId, chapterId) { mutableStateOf<Int?>(null) }
    var error by remember(sourceId, mangaId, chapterId) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var visibilityTick by remember { mutableStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }

    LaunchedEffect(sourceId, mangaId, chapterId) {
        error = null
        try {
            savedPage = library.progress(sourceId, mangaId, chapterId)?.page
            pages = catalog.pages(sourceId, mangaId, chapterId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Failed to load"
        }
    }

    val currentPages = pages
    // Restore the saved scroll position before we start observing it, so the initial value
    // fed into the debounced writer below is already the resumed page — never a spurious 0
    // that would race the restore and stomp the progress we just loaded.
    LaunchedEffect(currentPages) {
        if (currentPages == null) return@LaunchedEffect
        val saved = savedPage
        if (saved != null && saved in currentPages.indices) {
            listState.scrollToItem(saved)
        }
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .debounce(progressDebounceMillis)
            .collect { index -> library.setProgress(sourceId, mangaId, chapterId, page = index) }
    }

    LaunchedEffect(visibilityTick) {
        controlsVisible = true
        delay(CONTROLS_AUTO_HIDE_MS)
        controlsVisible = false
    }

    val currentError = error
    when {
        currentError != null -> Surface(modifier = Modifier.fillMaxSize(), color = ReaderBlack) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = currentError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        currentPages == null -> Surface(modifier = Modifier.fillMaxSize(), color = ReaderBlack) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        else -> {
            // Requesting focus here — not in a top-level LaunchedEffect(Unit) — matters: this
            // branch is the first point in composition where Modifier.focusRequester below is
            // actually attached to a node. Requesting focus before that (e.g. while the loading
            // spinner above is still showing) targets nothing and silently no-ops.
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPointerEvent(PointerEventType.Move) { visibilityTick++ }
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (keyEvent.key) {
                            Key.Spacebar, Key.PageDown, Key.DirectionDown -> {
                                scope.launch {
                                    listState.animateScrollBy(
                                        listState.layoutInfo.viewportSize.height * PAGE_SCROLL_FRACTION,
                                    )
                                }
                                true
                            }
                            Key.PageUp, Key.DirectionUp -> {
                                scope.launch {
                                    listState.animateScrollBy(
                                        -listState.layoutInfo.viewportSize.height * PAGE_SCROLL_FRACTION,
                                    )
                                }
                                true
                            }
                            Key.F -> {
                                onToggleFullscreen()
                                true
                            }
                            Key.Escape -> {
                                onBack()
                                true
                            }
                            else -> false
                        }
                    },
            ) {
                ReaderContent(
                    title = chapterLabel,
                    pages = currentPages,
                    listState = listState,
                    controlsVisible = controlsVisible,
                    onBack = onBack,
                    pageContent = pageContent,
                )
            }
        }
    }
}

/**
 * Default page renderer: a Coil [SubcomposeAsyncImage] carrying the page's host-required
 * headers (auth, referer) through [httpHeaders]. Plain `AsyncImage`'s placeholder slot only
 * takes a static Painter, so this uses SubcomposeAsyncImage to draw the composable loading
 * box the reader spec calls for (a tinted panel with a spinner) while a page is in flight.
 */
@Composable
private fun DefaultReaderPage(page: Page) {
    val context = LocalPlatformContext.current
    val request = remember(page) {
        val headers = NetworkHeaders.Builder().apply {
            page.headers.forEach { (name, value) -> set(name, value) }
        }.build()
        ImageRequest.Builder(context)
            .data(page.url)
            .httpHeaders(headers)
            // per-request: loader-level cap doesn't reach decode in coil 3.5.0 (ImageLoading.kt)
            .maxBitmapSize(WebtoonMaxBitmapSize)
            .build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        // pages render 800px sources into wider viewports; low (default) filtering pixelates
        filterQuality = FilterQuality.High,
        modifier = Modifier.fillMaxWidth(),
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(600.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        },
    )
}
