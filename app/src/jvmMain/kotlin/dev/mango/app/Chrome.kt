package dev.mango.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.mango.app.resources.Res
import dev.mango.app.resources.mango_icon
import dev.nucleusframework.window.noWindowDrag
import org.jetbrains.compose.resources.painterResource

internal const val SIDEBAR_TEST_TAG = "mango-sidebar"

internal const val SIDEBAR_TOGGLE_TAG = "sidebar-toggle"

internal val TITLE_BAR_HEIGHT = 44.dp

private val SIDEBAR_GUTTER = 10.dp
private val SIDEBAR_WIDTH = 264.dp
internal val SIDEBAR_EXTENT = SIDEBAR_GUTTER + SIDEBAR_WIDTH
internal val SIDEBAR_PEEK_EDGE = 12.dp

/** Back stays visible but inert when canGoBack is false; a mouse back press runs onMouseBack. */
@Composable
fun MangoTitleBar(
    sidebarOpen: Boolean,
    onToggleSidebar: () -> Unit,
    modifier: Modifier = Modifier,
    canGoBack: Boolean = false,
    onBack: () -> Unit = {},
    onMouseBack: () -> Unit = {},
    windowControls: @Composable BoxScope.() -> Unit = {},
) {
    val theme = LocalMangoTheme.current
    Box(modifier.sideButtons(onMouseBack).fillMaxWidth().height(TITLE_BAR_HEIGHT).background(theme.bg0)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TITLE_BAR_HEIGHT)
                .background(theme.bg0),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(8.dp))
            val backHover = rememberHoverFill(rest = theme.surface.copy(alpha = 0f), hover = theme.surface)
            Box(
                modifier = Modifier
                    .size(width = 34.dp, height = 28.dp)
                    .keyboardFocusRing(RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (canGoBack) Modifier.background(backHover.fill)
                            .hoverable(backHover.interaction) else Modifier
                    )
                    .noWindowDrag()
                    .clickable(
                        enabled = canGoBack,
                        interactionSource = backHover.interaction,
                        indication = null,
                        onClick = onBack,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.ChevronLeft,
                    contentDescription = "Back",
                    tint = if (canGoBack) theme.textSecondary else theme.textTertiary.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Image(
                painter = painterResource(Res.drawable.mango_icon),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            val hover = rememberHoverFill(
                rest = if (sidebarOpen) theme.surface else theme.surface.copy(alpha = 0f),
                hover = theme.surface,
            )
            Box(
                modifier = Modifier
                    .testTag(SIDEBAR_TOGGLE_TAG)
                    .size(width = 34.dp, height = 28.dp)
                    .keyboardFocusRing(RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(hover.fill)
                    .hoverable(hover.interaction)
                    .noWindowDrag()
                    // The animated fill is the indication; the default ripple would double-draw.
                    .clickable(interactionSource = hover.interaction, indication = null, onClick = onToggleSidebar),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ViewSidebar,
                    contentDescription = "Toggle sidebar",
                    tint = if (sidebarOpen) theme.accent else theme.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = "mango", style = MangoType.label, color = theme.textSecondary)
        }
        windowControls()
    }
}

data class ContinueItem(
    val sourceId: String,
    val mangaId: String,
    val title: String,
    val cover: String?,
    val progressLine: String,
)

private data class SidebarNavItem(val label: String, val icon: ImageVector, val screen: Screen)

private val SIDEBAR_NAV_ITEMS = listOf(
    SidebarNavItem("Library", Icons.AutoMirrored.Outlined.LibraryBooks, Screen.Library),
    SidebarNavItem("Browse", Icons.Outlined.Explore, Screen.Browse),
    SidebarNavItem("Search", Icons.Outlined.Search, Screen.Search),
    SidebarNavItem("Downloads", Icons.Outlined.Download, Screen.Downloads),
    SidebarNavItem("Extensions", Icons.Outlined.Extension, Screen.Extensions),
    SidebarNavItem("Settings", Icons.Outlined.Settings, Screen.Settings),
)

/** Peeks the sidebar while the pointer touches the left edge, until it moves past the panel or leaves. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.sidebarPeek(enabled: Boolean, onPeekChange: (Boolean) -> Unit): Modifier {
    val density = LocalDensity.current
    val edgePx = with(density) { SIDEBAR_PEEK_EDGE.toPx() }
    val extentPx = with(density) { SIDEBAR_EXTENT.toPx() }
    if (!enabled) return this
    val track: AwaitPointerEventScope.(PointerEvent) -> Unit = { event ->
        val x = event.changes.first().position.x
        if (x <= edgePx) onPeekChange(true) else if (x > extentPx) onPeekChange(false)
    }
    return this
        .onPointerEvent(PointerEventType.Enter, PointerEventPass.Initial, track)
        .onPointerEvent(PointerEventType.Move, PointerEventPass.Initial, track)
        .onPointerEvent(PointerEventType.Exit) { onPeekChange(false) }
}

@Composable
fun Sidebar(
    visible: Boolean,
    continueItems: List<ContinueItem>,
    activeScreen: Screen,
    pendingDownloadCount: Int,
    onNavigate: (Screen) -> Unit,
    onContinue: (ContinueItem) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
) {
    val theme = LocalMangoTheme.current
    val motion = LocalMangoMotion.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = (if (motion.slides) {
            slideInHorizontally(
                animationSpec = tween(motion.sidebarOpenMs, easing = motion.decel),
            ) { fullWidth -> -fullWidth }
        } else {
            EnterTransition.None
        }) + fadeIn(
            animationSpec = tween(motion.sidebarOpenMs, easing = motion.decel),
        ),
        exit = (if (motion.slides) {
            slideOutHorizontally(
                animationSpec = tween(motion.sidebarCloseMs, easing = motion.standard),
            ) { fullWidth -> -fullWidth }
        } else {
            ExitTransition.None
        }) + fadeOut(
            animationSpec = tween(motion.sidebarCloseMs, easing = motion.standard),
        ),
    ) {
        Column(
            modifier = Modifier
                .testTag(SIDEBAR_TEST_TAG)
                .padding(start = SIDEBAR_GUTTER, bottom = 10.dp)
                .fillMaxHeight()
                .width(SIDEBAR_WIDTH)
                .shadow(elevation = 24.dp, shape = RoundedCornerShape(MangoRadius.panel))
                .clip(RoundedCornerShape(MangoRadius.panel))
                .then(
                    if (hazeState != null) {
                        Modifier.hazeEffect(
                            state = hazeState,
                            style = HazeDefaults.style(
                                backgroundColor = theme.bg1,
                                tint = HazeTint(theme.overlay),
                                blurRadius = 24.dp,
                            ),
                        )
                    } else {
                        Modifier.background(theme.overlay)
                    },
                )
                // An input node blocks hit testing behind the panel without consuming events.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                .padding(vertical = 16.dp, horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (continueItems.isNotEmpty()) {
                SidebarStaggerGroup(groupIndex = 0) {
                    Text(
                        text = "CONTINUE READING",
                        style = MangoType.microLabel,
                        color = theme.textTertiary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    continueItems.forEach { item ->
                        ContinueCard(item = item, onClick = { onContinue(item) })
                    }
                    HorizontalDivider(
                        color = theme.divider,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
            }
            SidebarStaggerGroup(groupIndex = if (continueItems.isNotEmpty()) 1 else 0) {
                SIDEBAR_NAV_ITEMS.forEach { nav ->
                    SidebarNavRow(
                        nav = nav,
                        active = nav.screen == activeScreen,
                        pill = if (nav.screen == Screen.Downloads && pendingDownloadCount > 0) {
                            pendingDownloadCount.toString()
                        } else {
                            null
                        },
                        onClick = { onNavigate(nav.screen) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SidebarStaggerGroup(groupIndex: Int, content: @Composable ColumnScope.() -> Unit) {
    val motion = LocalMangoMotion.current
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val delayMs = (groupIndex * motion.sidebarStaggerMs).coerceAtMost(motion.sidebarStaggerCapMs)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = motion.sidebarOpenMs,
                delayMillis = delayMs,
                easing = motion.decel
            ),
        )
    }
    Column(
        modifier = Modifier.graphicsLayer {
            alpha = progress.value
            if (motion.slides) translationY = (1f - progress.value) * 6.dp.toPx()
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
private fun ContinueCard(item: ContinueItem, onClick: () -> Unit) {
    val theme = LocalMangoTheme.current
    val hover = rememberHoverFill(rest = theme.surface.copy(alpha = 0f), hover = theme.surface)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .keyboardFocusRing(RoundedCornerShape(MangoRadius.control))
            .clip(RoundedCornerShape(MangoRadius.control))
            .background(hover.fill)
            .hoverable(hover.interaction)
            .clickable(interactionSource = hover.interaction, indication = null, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 30.dp, height = 40.dp)
                .clip(RoundedCornerShape(MangoRadius.keycap))
                .background(theme.bg2),
        ) {
            if (item.cover != null) {
                AsyncImage(
                    model = rememberCoverRequest(item.sourceId, item.cover),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = theme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.progressLine,
                style = MangoType.hint,
                color = theme.textTertiary,
            )
        }
    }
}

@Composable
private fun SidebarNavRow(nav: SidebarNavItem, active: Boolean, pill: String?, onClick: () -> Unit) {
    val theme = LocalMangoTheme.current
    val hover = rememberHoverFill(
        rest = if (active) theme.accent.copy(alpha = 0.12f) else theme.surface.copy(alpha = 0f),
        hover = if (active) theme.accent.copy(alpha = 0.12f) else theme.surface,
    )
    val contentColor = if (active) theme.accent else theme.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .keyboardFocusRing(RoundedCornerShape(MangoRadius.control))
            .clip(RoundedCornerShape(MangoRadius.control))
            .background(hover.fill)
            .hoverable(hover.interaction)
            .clickable(interactionSource = hover.interaction, indication = null, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = nav.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = nav.label,
            style = MangoType.label,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        if (pill != null) {
            Pill(
                text = pill,
                container = theme.surface,
                content = theme.textSecondary,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}
