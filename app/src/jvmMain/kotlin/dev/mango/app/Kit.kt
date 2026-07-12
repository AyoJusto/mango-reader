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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/**
 * Shared visual primitives every screen restyle builds on: reads [LocalMangoTheme]
 * exclusively, never a hardcoded color. Components take data and lambdas only — no
 * screen-specific logic.
 */

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
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()

    val restFill = when (style) {
        KitButtonStyle.PRIMARY -> theme.accent
        KitButtonStyle.SECONDARY -> theme.surface
        KitButtonStyle.GHOST -> theme.surface.copy(alpha = 0f)
        KitButtonStyle.DANGER -> theme.danger.copy(alpha = 0.14f)
    }
    val hoverFill = if (style == KitButtonStyle.GHOST) theme.surface else restFill
    val fill by animateColorAsState(
        targetValue = if (hovered) hoverFill else restFill,
        animationSpec = tween(MangoMotion.HOVER_MS),
    )
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
            .background(fill)
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
            fontSize = 13.sp,
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
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(
        // Same-color-at-zero-alpha rest state; see Chrome.kt's title-bar glyph for why.
        targetValue = if (hovered) theme.surface else theme.surface.copy(alpha = 0f),
        animationSpec = tween(MangoMotion.HOVER_MS),
    )
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(fill)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
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
            .clip(RoundedCornerShape(999.dp))
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
        // Same-color-at-zero-alpha rest state; see Chrome.kt's title-bar glyph for why.
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
                    Text(text = metaLine, fontSize = 11.5.sp, color = theme.textSecondary)
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
            fontSize = 11.5.sp,
            color = theme.textTertiary,
        )
    }
}

/** Step 1 of the challenge flow: a source's site is being solved, inline where content would load. */
@Composable
fun ChallengeSolvingCard(host: String, modifier: Modifier = Modifier) {
    val theme = LocalMangoTheme.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(MangoRadius.row))
            .background(theme.bg2)
            .padding(horizontal = MangoSpace.md, vertical = MangoSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = theme.accent,
            strokeWidth = 2.dp,
            trackColor = Color.Transparent,
        )
        Column {
            Text(text = "Solving $host's challenge…", style = MangoType.bodyStrong, color = theme.textPrimary)
            Text(text = "~15 s", style = MangoType.caption, color = theme.textTertiary)
        }
    }
}

/**
 * Step 2 of the challenge flow, on failure: the user did nothing wrong, so this reads as
 * warning — never danger — with a manual-solve escape hatch and a retry.
 */
@Composable
fun ChallengeFailedCard(
    host: String,
    onSolveManually: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalMangoTheme.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(MangoRadius.row))
            .background(theme.warning.copy(alpha = 0.10f))
            .padding(MangoSpace.md),
        verticalArrangement = Arrangement.spacedBy(MangoSpace.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm)) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(theme.warning),
            )
            Text(text = "$host needs a manual challenge solve", style = MangoType.bodyStrong, color = theme.textPrimary)
        }
        Text(
            text = "This site checks that you're not a bot before it lets mango in. Solve it once and mango remembers.",
            style = MangoType.caption,
            color = theme.textSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm)) {
            KitButton(label = "Solve manually…", onClick = onSolveManually, style = KitButtonStyle.PRIMARY)
            KitButton(label = "Retry", onClick = onRetry, style = KitButtonStyle.SECONDARY)
        }
    }
}
