package dev.mango.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * Shared visual primitives every screen restyle builds on: reads [LocalMangoTheme]
 * exclusively, never a hardcoded color. Components take data and lambdas only — no
 * screen-specific logic.
 */

/**
 * Centers screen content in a width-capped column, web-page style. Below [max] the content
 * fills the window exactly as an unwrapped `fillMaxWidth` would; beyond it, the column stops
 * growing and centers in the remaining space. Use [MangoSpace.contentMaxWidth] for reading/form
 * screens and [MangoSpace.gridMaxWidth] for cover grids. The reader never uses this.
 */
@Composable
fun ContentColumn(
    max: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = max).fillMaxSize(), content = content)
    }
}

/** Shimmer placeholder for a reserved shape whose content is still loading: bg1-bg2-bg1 sweep. */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier) {
    val theme = LocalMangoTheme.current
    val transition = rememberInfiniteTransition(label = "skeleton")
    val color by transition.animateColor(
        initialValue = theme.bg1,
        targetValue = theme.bg2,
        animationSpec = infiniteRepeatable(
            animation = tween(MangoMotion.SHIMMER_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-color",
    )
    Box(modifier = modifier.background(color))
}

/**
 * A hoverable's [interaction] source paired with its animated hover [fill]. [interaction] is
 * exposed so the same source can also drive `.hoverable()`, `.clickable()`, and any other
 * interaction-derived state a caller needs (a pressed-scale animation, say), instead of forcing
 * a second [MutableInteractionSource] to be threaded through by hand.
 */
class HoverFill(val interaction: MutableInteractionSource, fillState: State<Color>) {
    val fill: Color by fillState
}

/**
 * Fades between [rest] and [hover] as the returned [HoverFill.interaction] reports hover state.
 * [rest] must be [hover] at zero alpha — never [Color.Transparent]: a fade toward transparent
 * black passes through darker mid-frames on the way out, a visible flash at the end of the
 * hover-exit animation that an alpha-only fade of the same color never produces.
 */
@Composable
fun rememberHoverFill(rest: Color, hover: Color): HoverFill {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill = animateColorAsState(
        targetValue = if (hovered) hover else rest,
        animationSpec = tween(MangoMotion.HOVER_MS),
    )
    return HoverFill(interaction, fill)
}

/** The four button treatments every action in the app uses; see [KitButton]. */
enum class KitButtonStyle { PRIMARY, SECONDARY, GHOST, DANGER }

/**
 * The one button component for the app: fill and text color come from [style], height and
 * radius are shared across all four. Hover is a fill-only fade (alpha-only, same color at zero
 * alpha at rest — never [androidx.compose.ui.graphics.Color.Transparent] — so the exit never
 * flashes); [KitButtonStyle.GHOST] is the only style whose fill actually changes on hover, since
 * the other three are already filled at rest. Press is a uniform 0.97 scale. No ripple: the
 * animated fill and scale together are the indication.
 */
@Composable
fun KitButton(
    label: String,
    onClick: () -> Unit,
    style: KitButtonStyle,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val theme = LocalMangoTheme.current
    val restFill = when (style) {
        KitButtonStyle.PRIMARY -> theme.accent
        KitButtonStyle.SECONDARY -> theme.surface
        KitButtonStyle.GHOST -> theme.surface.copy(alpha = 0f)
        KitButtonStyle.DANGER -> theme.danger.copy(alpha = 0.14f)
    }
    val hoverFill = if (style == KitButtonStyle.GHOST) theme.surface else restFill
    val hover = rememberHoverFill(rest = restFill, hover = hoverFill)
    val interaction = hover.interaction
    val pressed by interaction.collectIsPressedAsState()
    val contentColor = when (style) {
        KitButtonStyle.PRIMARY -> theme.accentOn
        KitButtonStyle.SECONDARY -> theme.textPrimary
        KitButtonStyle.GHOST -> theme.textSecondary
        KitButtonStyle.DANGER -> theme.danger
    }
    val scale by animateFloatAsState(
        targetValue = if (pressed) MangoMotion.PRESS_SCALE else 1f,
        animationSpec = tween(MangoMotion.PRESS_MS, easing = MangoMotion.standard),
    )
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(38.dp)
            .clip(RoundedCornerShape(MangoRadius.control))
            .background(hover.fill)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = MangoSpace.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MangoType.bodyStrong,
            color = if (enabled) contentColor else contentColor.copy(alpha = 0.4f),
        )
    }
}

/**
 * Centered placeholder for a list or screen with nothing in it yet: a dashed 2:3 outline (the
 * cover-card silhouette), a title, guidance text, and an optional single CTA.
 */
@Composable
fun EmptyState(
    title: String,
    guidance: String,
    ctaLabel: String? = null,
    onCta: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val theme = LocalMangoTheme.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MangoSpace.sm),
    ) {
        Canvas(modifier = Modifier.size(width = 120.dp, height = 180.dp)) {
            drawRoundRect(
                color = theme.textPrimary.copy(alpha = 0.2f),
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                ),
                cornerRadius = CornerRadius(MangoRadius.row.toPx()),
            )
        }
        Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = theme.textPrimary)
        Text(
            text = guidance,
            style = MangoType.label,
            color = theme.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (ctaLabel != null && onCta != null) {
            KitButton(label = ctaLabel, onClick = onCta, style = KitButtonStyle.PRIMARY)
        }
    }
}

/**
 * A dismissible, non-blocking failure notice: a background action failed but whatever content
 * is already on screen stays interactive. Reused wherever a background action can fail, not
 * just Extensions. Slides down and fades in on first composition.
 */
@Composable
fun ErrorBanner(
    headline: String,
    detail: String,
    onRetry: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalMangoTheme.current
    val visibleState = remember { MutableTransitionState(false) }.apply { targetState = true }
    AnimatedVisibility(
        visibleState = visibleState,
        modifier = modifier,
        enter = slideInVertically(
            animationSpec = tween(MangoMotion.BANNER_IN_MS, easing = MangoMotion.decel),
        ) { height -> -height / 2 } + fadeIn(
            animationSpec = tween(MangoMotion.BANNER_IN_MS, easing = MangoMotion.decel),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MangoRadius.row))
                .background(theme.danger.copy(alpha = 0.10f))
                .padding(horizontal = MangoSpace.md, vertical = MangoSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(theme.danger),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = headline, style = MangoType.bodyStrong, color = theme.textPrimary)
                Text(text = detail, style = MangoType.caption, color = theme.textSecondary)
            }
            if (onRetry != null) {
                ErrorBannerRetryButton(onClick = onRetry)
            }
            ErrorBannerDismissButton(onClick = onDismiss)
        }
    }
}

@Composable
private fun ErrorBannerRetryButton(onClick: () -> Unit) {
    val theme = LocalMangoTheme.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) MangoMotion.PRESS_SCALE else 1f,
        animationSpec = tween(MangoMotion.PRESS_MS, easing = MangoMotion.standard),
    )
    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(MangoRadius.control))
            .background(theme.danger.copy(alpha = 0.16f))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = MangoSpace.sm, vertical = MangoSpace.base),
    ) {
        Text(text = "Retry", style = MangoType.bodyStrong, color = theme.danger)
    }
}

@Composable
private fun ErrorBannerDismissButton(onClick: () -> Unit) {
    val theme = LocalMangoTheme.current
    val hover = rememberHoverFill(rest = theme.surface.copy(alpha = 0f), hover = theme.surface)
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(hover.fill)
            .hoverable(hover.interaction)
            .clickable(interactionSource = hover.interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Dismiss",
            tint = theme.textSecondary,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** A small rounded label chip: caller supplies both fill and text color. */
@Composable
fun Pill(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.SemiBold,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MangoRadius.pill))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text = text, fontSize = 11.sp, fontWeight = fontWeight, color = content)
    }
}

/** A keyboard-hint chip, e.g. for the command palette's shortcut hints. */
@Composable
fun Keycap(text: String, modifier: Modifier = Modifier) {
    val theme = LocalMangoTheme.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MangoRadius.keycap))
            .background(theme.textPrimary.copy(alpha = 0.07f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = text, style = MangoType.monoKeycap, color = theme.textPrimary)
    }
}

private val KIT_SEARCH_FIELD_RADIUS = MangoRadius.row
private val KIT_SEARCH_FIELD_RING_RADIUS = KIT_SEARCH_FIELD_RADIUS + 2.dp

/**
 * The app's styled text query field: bg2 fill, rounded, a focus ring offset from the control by
 * a bg gap, an accent caret, and a leading search glyph. Single-line; Enter (IME search action)
 * fires [onSearch]. [placeholder] shows only while [value] is empty.
 */
@Composable
fun KitSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalMangoTheme.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val ring = if (focused) {
        Modifier.border(2.dp, theme.focus, RoundedCornerShape(KIT_SEARCH_FIELD_RING_RADIUS))
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .then(ring)
            .padding(2.dp)
            .clip(RoundedCornerShape(KIT_SEARCH_FIELD_RADIUS))
            .background(theme.bg2)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm)) {
            Text(text = "⌕", style = MangoType.body, color = theme.textTertiary)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                textStyle = MangoType.body.copy(color = theme.textPrimary),
                singleLine = true,
                cursorBrush = SolidColor(theme.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                interactionSource = interaction,
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty()) {
                            Text(text = placeholder, style = MangoType.body, color = theme.textTertiary)
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}

/**
 * A horizontal progress bar: [progress] animates width changes, and with [successAtFull] the
 * fill turns [MangoTheme.success] once it reaches 1f (a finished chapter/download reads as
 * done, not just "full accent"); pass false to keep the fill accent throughout (the reader's
 * position bar, where 1f is just "at the bottom", not an achievement). [trackColor] overrides
 * the resting-track fill (e.g. the reader overlay's white-on-dark track); null keeps the
 * default [MangoTheme.bg2].
 */
@Composable
fun ProgressTrack(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    trackColor: Color? = null,
    successAtFull: Boolean = true,
) {
    val theme = LocalMangoTheme.current
    val clamped = progress.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = clamped,
        animationSpec = tween(MangoMotion.PROGRESS_BAR_MS, easing = MangoMotion.standard),
    )
    val fillColor = if (successAtFull && clamped >= 1f) theme.success else theme.accent
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(trackColor ?: theme.bg2),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .clip(RoundedCornerShape(height / 2))
                .background(fillColor),
        )
    }
}

/** A row of mutually-exclusive text segments, e.g. the Library grid/list toggle. */
@Composable
fun SegmentedControl(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalMangoTheme.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(MangoRadius.control))
            .background(theme.bg2)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, option ->
            SegmentedControlOption(
                text = option,
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
            )
        }
    }
}

@Composable
private fun SegmentedControlOption(text: String, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalMangoTheme.current
    val interaction = remember { MutableInteractionSource() }
    val fill by animateColorAsState(
        // Same-color-at-zero-alpha rest state; see rememberHoverFill's KDoc for why.
        targetValue = if (selected) theme.surface else theme.surface.copy(alpha = 0f),
        animationSpec = tween(MangoMotion.HOVER_MS),
    )
    Box(
        modifier = Modifier
            .then(if (selected) Modifier.shadow(elevation = 2.dp, shape = RoundedCornerShape(8.dp)) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = MangoSpace.sm, vertical = MangoSpace.base),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MangoType.caption,
            color = if (selected) theme.textPrimary else theme.textSecondary,
        )
    }
}

/**
 * The Library/Browse cover-card grammar: a 2:3 cover with a title and meta line beneath. Rest
 * shows an unread-count pill; hovering scales the cover up and reveals a scrim with the meta
 * line and a progress bar; a finished series dims the cover and swaps the pill for a checkmark.
 */
@Composable
fun CoverCard(
    title: String,
    coverUrl: String?,
    metaLine: String,
    unreadCount: Int?,
    progress: Float?,
    finished: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalMangoTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = if (hovered) MangoMotion.COVER_HOVER_SCALE else 1f,
        animationSpec = tween(MangoMotion.COVER_HOVER_MS, easing = MangoMotion.decel),
    )
    val scrimAlpha by animateFloatAsState(
        targetValue = if (hovered) 1f else 0f,
        animationSpec = tween(MangoMotion.COVER_HOVER_MS, easing = MangoMotion.decel),
    )
    Column(
        modifier = modifier
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .then(if (hovered) Modifier.shadow(elevation = 16.dp, shape = RoundedCornerShape(MangoRadius.row)) else Modifier)
                .clip(RoundedCornerShape(MangoRadius.row))
                .background(theme.bg2),
        ) {
            if (coverUrl != null) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    // Only the artwork dims when finished; the ✓ pill above stays crisp.
                    modifier = Modifier.fillMaxSize().alpha(if (finished) 0.7f else 1f),
                )
            }
            when {
                finished -> Pill(
                    text = "✓",
                    container = theme.success.copy(alpha = 0.2f),
                    content = theme.success,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                )
                unreadCount != null && unreadCount > 0 -> Pill(
                    text = unreadCount.toString(),
                    container = theme.accent.copy(alpha = 0.92f),
                    content = theme.accentOn,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .alpha(scrimAlpha)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, theme.bg0.copy(alpha = 0.85f))))
                    .padding(8.dp),
            ) {
                Column {
                    Text(text = metaLine, style = MangoType.meta, color = theme.textSecondary)
                    if (progress != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        ProgressTrack(progress = progress, height = 3.dp)
                    }
                }
            }
        }
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = theme.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = if (finished) "Completed" else metaLine,
            style = MangoType.meta,
            color = theme.textTertiary,
        )
    }
}

private val KIT_DROPDOWN_MAX_MENU_HEIGHT = 320.dp

/** Fixed option-row height; the type-ahead scroll math derives offsets from it, so they cannot drift. */
private val KIT_DROPDOWN_ITEM_HEIGHT = 36.dp

/** Idle time after which the type-ahead prefix buffer resets and typing starts a new prefix. */
private const val KIT_DROPDOWN_TYPE_AHEAD_RESET_MS = 750L

/**
 * The app's single-select dropdown: a kit-styled trigger row showing the current value, opening
 * a scrollable menu of [options]. The menu is at least as wide as the trigger (wider when an
 * option needs it); the selected option renders in accent color. Selection is by value, not
 * index — [onSelect] receives the clicked option string.
 *
 * Keyboard: typing jumps the active row to the first prefix match (case-insensitive, transient
 * buffer), Up/Down step it without wrapping, Enter selects it, Escape dismisses. The active row
 * shares the hover fill treatment; it starts on the selected option, scrolled into view. While a
 * prefix is buffered, the trigger echoes it in accent color so typing has visible feedback.
 */
@Composable
fun KitDropdown(
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalMangoTheme.current
    var expanded by remember { mutableStateOf(false) }
    var triggerWidthPx by remember { mutableIntStateOf(0) }
    val hover = rememberHoverFill(rest = theme.surface, hover = theme.bg2)
    val typeAhead = remember(expanded) { TypeAheadState(options, options.indexOf(selected)) }
    Box(modifier = modifier) {
        // The trigger stays visible above the open menu, so it doubles as the type-ahead echo:
        // while a prefix is buffered it shows what was typed instead of the selected value.
        val typing = expanded && typeAhead.buffer.isNotEmpty()
        Row(
            modifier = Modifier
                .onSizeChanged { triggerWidthPx = it.width }
                .height(38.dp)
                .clip(RoundedCornerShape(MangoRadius.control))
                .background(hover.fill)
                .hoverable(hover.interaction)
                .clickable(interactionSource = hover.interaction, indication = null) { expanded = true }
                .padding(horizontal = MangoSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MangoSpace.xs),
        ) {
            Text(
                text = if (typing) typeAhead.buffer else selected,
                style = MangoType.body,
                color = if (typing) theme.accent else theme.textPrimary,
            )
            Text(text = "▾", style = MangoType.body, color = theme.textTertiary)
        }
        val density = LocalDensity.current
        val triggerWidth = with(density) { triggerWidthPx.toDp() }
        val itemHeightPx = with(density) { KIT_DROPDOWN_ITEM_HEIGHT.roundToPx() }
        val scrollState = rememberScrollState()
        val menuFocus = remember { FocusRequester() }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            scrollState = scrollState,
            shape = RoundedCornerShape(MangoRadius.panel),
            containerColor = theme.bg2,
            modifier = Modifier
                .widthIn(min = triggerWidth)
                .heightIn(max = KIT_DROPDOWN_MAX_MENU_HEIGHT)
                .focusRequester(menuFocus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            typeAhead.onArrowDown()
                            true
                        }
                        Key.DirectionUp -> {
                            typeAhead.onArrowUp()
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            options.getOrNull(typeAhead.activeIndex)?.let {
                                expanded = false
                                onSelect(it)
                            }
                            true
                        }
                        else -> {
                            val char = event.utf16CodePoint.toChar()
                            if (char.code >= 32 && !char.isISOControl()) {
                                typeAhead.onChar(char)
                                true
                            } else {
                                false
                            }
                        }
                    }
                },
        ) {
            LaunchedEffect(Unit) { menuFocus.requestFocus() }
            LaunchedEffect(typeAhead.buffer) {
                if (typeAhead.buffer.isNotEmpty()) {
                    delay(KIT_DROPDOWN_TYPE_AHEAD_RESET_MS)
                    typeAhead.clearBuffer()
                }
            }
            LaunchedEffect(typeAhead.activeIndex) {
                val targetTop = typeAhead.activeIndex * itemHeightPx
                val viewport = scrollState.viewportSize
                if (targetTop < scrollState.value) {
                    scrollState.animateScrollTo(targetTop)
                } else if (targetTop + itemHeightPx > scrollState.value + viewport) {
                    scrollState.animateScrollTo(targetTop + itemHeightPx - viewport)
                }
            }
            options.forEachIndexed { index, option ->
                KitDropdownItem(
                    text = option,
                    selected = option == selected,
                    active = index == typeAhead.activeIndex,
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun KitDropdownItem(text: String, selected: Boolean, active: Boolean, onClick: () -> Unit) {
    val theme = LocalMangoTheme.current
    val hover = rememberHoverFill(
        rest = if (active) theme.bg1 else theme.bg1.copy(alpha = 0f),
        hover = theme.bg1,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(KIT_DROPDOWN_ITEM_HEIGHT)
            .background(hover.fill)
            .hoverable(hover.interaction)
            .clickable(interactionSource = hover.interaction, indication = null, onClick = onClick)
            .padding(horizontal = MangoSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MangoType.body,
            color = if (selected) theme.accent else theme.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

