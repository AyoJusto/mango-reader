package dev.mango.app

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import java.awt.Frame
import java.lang.reflect.Method
import java.util.logging.Level
import java.util.logging.Logger

private val chromeLog = Logger.getLogger("Chrome")

/** Test hook: scopes sidebar node lookups to the panel's subtree (same idiom as [PALETTE_TEST_TAG]). */
internal const val SIDEBAR_TEST_TAG = "mango-sidebar"

/** Test hook: the title-bar glyph that toggles the sidebar. */
internal const val SIDEBAR_TOGGLE_TAG = "sidebar-toggle"

/**
 * Handle to a JBR custom title bar merged into the window: the native bar object, its
 * hit-test hook, and the pixel width the OS reserves for its own controls on the right —
 * captured once at apply time, never hardcoded (it varies with OS, scale, and JBR version).
 */
class JbrBar internal constructor(
    private val bar: Any,
    private val forceHitTestMethod: Method,
    val rightInset: Float,
) {
    /**
     * Reports the current pointer position's ownership to the native bar: `true` claims it for
     * the client (a control is in use), `false` lets the OS treat it as a drag region.
     */
    fun forceHitTest(client: Boolean) {
        forceHitTestMethod.invoke(bar, client)
    }
}

/**
 * Merges a [heightPx]-tall custom title bar into [frame] via the JBR WindowDecorations API,
 * reflectively — no compile-time JBR dependency. Returns null on any failure (stock JDK,
 * headless, older JBR): a normal OS-decorated window is a fully supported mode, so absence is
 * logged at INFO and never thrown.
 */
fun applyJbrTitleBar(frame: Frame, heightPx: Float): JbrBar? = try {
    val jbr = Class.forName("com.jetbrains.JBR")
    val wd = jbr.getMethod("getWindowDecorations").invoke(null)
    if (wd == null) {
        null
    } else {
        val wdIface = Class.forName("com.jetbrains.WindowDecorations")
        val barIface = Class.forName("com.jetbrains.WindowDecorations\$CustomTitleBar")
        val bar = wdIface.getMethod("createCustomTitleBar").invoke(wd)
        barIface.getMethod("setHeight", Float::class.javaPrimitiveType).invoke(bar, heightPx)
        wdIface.getMethod("setCustomTitleBar", Frame::class.java, barIface).invoke(wd, frame, bar)
        val force = barIface.getMethod("forceHitTest", Boolean::class.javaPrimitiveType)
        val rightInset = barIface.getMethod("getRightInset").invoke(bar) as Float
        JbrBar(bar, force, rightInset)
    }
} catch (t: Throwable) {
    chromeLog.log(Level.INFO, "JBR custom title bar unavailable, falling back to OS decorations", t)
    null
}

/**
 * The Compose side of the JBR hit-test handshake: Compose is one AWT canvas, so every
 * unconsumed pointer event over the bar must report `false` (native drag/double-click work) and
 * consumed events (a child control in use) report `true` until release. No-op when [bar] is null.
 */
fun Modifier.jbrHitTest(bar: JbrBar?): Modifier {
    if (bar == null) return this
    return pointerInput(bar) {
        awaitPointerEventScope {
            var inControl = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val consumed = event.changes.any { it.isConsumed }
                if (consumed || inControl) {
                    if (event.type == PointerEventType.Press) inControl = true
                    if (event.type == PointerEventType.Release) inControl = false
                    bar.forceHitTest(true)
                } else {
                    bar.forceHitTest(false)
                }
            }
        }
    }
}

/** The merged title bar's height; also the height requested from the JBR native bar. */
internal val TITLE_BAR_HEIGHT = 44.dp

/**
 * The 44dp merged title bar: sidebar-toggle glyph and app title on the left, a spacer on the
 * right reserving the OS-drawn window controls' width. Never draws min/max/close itself — they
 * are native in JBR mode, and the OS bar exists in the fallback.
 */
@Composable
fun MangoTitleBar(jbrBar: JbrBar?, sidebarOpen: Boolean, onToggleSidebar: () -> Unit) {
    val theme = LocalMangoTheme.current
    val density = LocalDensity.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TITLE_BAR_HEIGHT)
            .background(theme.bg0)
            .jbrHitTest(jbrBar),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(12.dp))
        Image(
            painter = painterResource("mango-icon.svg"),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        // sidebarOpen folds into both rest and hover so the toggle stays lit while the
        // sidebar is open, regardless of live hover state.
        val hover = rememberHoverFill(
            rest = if (sidebarOpen) theme.surface else theme.surface.copy(alpha = 0f),
            hover = theme.surface,
        )
        Box(
            modifier = Modifier
                .testTag(SIDEBAR_TOGGLE_TAG)
                .size(width = 34.dp, height = 28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(hover.fill)
                .hoverable(hover.interaction)
                // The animated fill IS the indication; the default ripple would double-draw
                // and flash its own hover layer on exit.
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
        Spacer(modifier = Modifier.weight(1f))
        val rightInset = jbrBar?.let { with(density) { it.rightInset.toDp() } } ?: 0.dp
        Spacer(modifier = Modifier.width(rightInset))
    }
}

/** One resumable series shown as a Continue-reading card in the sidebar. */
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

/**
 * The collapsible overlay sidebar: Continue-reading cards on top, then the nav items. It
 * overlays the content area (never pushes it) as a frosted panel: the overlay token's alpha
 * plus a backdrop blur of the content behind it. Without [hazeState] (component previews,
 * screenshots) the fill falls back to the translucent token alone.
 */
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
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInHorizontally(
            animationSpec = tween(MangoMotion.SIDEBAR_OPEN_MS, easing = MangoMotion.decel),
        ) { fullWidth -> -fullWidth } + fadeIn(
            animationSpec = tween(MangoMotion.SIDEBAR_OPEN_MS, easing = MangoMotion.decel),
        ),
        exit = slideOutHorizontally(
            animationSpec = tween(MangoMotion.SIDEBAR_CLOSE_MS, easing = MangoMotion.standard),
        ) { fullWidth -> -fullWidth } + fadeOut(
            animationSpec = tween(MangoMotion.SIDEBAR_CLOSE_MS, easing = MangoMotion.standard),
        ),
    ) {
        Column(
            modifier = Modifier
                .testTag(SIDEBAR_TEST_TAG)
                .padding(start = 10.dp, bottom = 10.dp)
                .fillMaxHeight()
                .width(264.dp)
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
                // An input node makes this subtree opaque to hit testing: clicks and hovers
                // stop at the panel instead of reaching the screen behind it. Events are not
                // consumed, so the panel's own controls behave normally.
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
            // Offset by how many groups actually rendered before this one, not a fixed slot:
            // with no continue items, nav is the first (and only) group, so it opens immediately.
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

/**
 * One stagger group of the sidebar's contents: fades and rises in on first composition (the
 * panel opening), offset by [groupIndex] * [MangoMotion.SIDEBAR_STAGGER_MS] capped at
 * [MangoMotion.SIDEBAR_STAGGER_CAP_MS]. Content stays composed at full opacity once settled, so
 * closing the sidebar is just the outer panel's own slide-out — no separate exit animation here.
 */
@Composable
private fun SidebarStaggerGroup(groupIndex: Int, content: @Composable ColumnScope.() -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val delayMs = (groupIndex * MangoMotion.SIDEBAR_STAGGER_MS).coerceAtMost(MangoMotion.SIDEBAR_STAGGER_CAP_MS)
        progress.animateTo(
            targetValue = 1f,
            // delayMillis, not a raw suspend delay(): stays on the same frame-clock-driven
            // timing every other animation in this file uses.
            animationSpec = tween(
                durationMillis = MangoMotion.SIDEBAR_OPEN_MS,
                delayMillis = delayMs,
                easing = MangoMotion.decel
            ),
        )
    }
    Column(
        modifier = Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 6.dp.toPx()
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
            .clip(RoundedCornerShape(MangoRadius.control))
            .background(hover.fill)
            .hoverable(hover.interaction)
            // Same rule as the title-bar glyph: the animated fill is the only indication.
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
                    model = item.cover,
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
    // active folds into both rest and hover so it wins over live hover state, same idiom as
    // the title-bar glyph's sidebarOpen.
    val hover = rememberHoverFill(
        rest = if (active) theme.accent.copy(alpha = 0.12f) else theme.surface.copy(alpha = 0f),
        hover = if (active) theme.accent.copy(alpha = 0.12f) else theme.surface,
    )
    val contentColor = if (active) theme.accent else theme.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MangoRadius.control))
            .background(hover.fill)
            .hoverable(hover.interaction)
            // Same rule as the title-bar glyph: the animated fill is the only indication.
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
