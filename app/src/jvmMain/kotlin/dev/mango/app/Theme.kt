package dev.mango.app

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The app's full semantic color set. Every screen reads colors through [LocalMangoTheme] —
 * never a hardcoded [Color] — so switching themes or importing a JSON file recolors the whole
 * app without touching a screen. Exported/imported as JSON by [ThemeJson]; [focus] is the one
 * token deliberately excluded from that file because it is always derived from [accent].
 */
data class MangoTheme(
    val name: String,
    val bg0: Color,
    val bg1: Color,
    val bg2: Color,
    val surface: Color,
    val overlay: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val accent: Color,
    val accentOn: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
) {
    /** Keyboard focus ring color: the current accent at reduced opacity — never stored or exported. */
    val focus: Color get() = accent.copy(alpha = 0.45f)
}

/** The built-in default theme; also the fallback when no theme file exists or it fails to load. */
val MangoDark = MangoTheme(
    name = "Mango Dark",
    bg0 = Color(0xFF0D0D0F),
    bg1 = Color(0xFF141417),
    bg2 = Color(0xFF1B1B1F),
    surface = Color(0xFF232328),
    overlay = Color(0xDB18181C),
    textPrimary = Color(0xFFF4F4F6),
    textSecondary = Color(0xA3F4F4F6),
    textTertiary = Color(0x66F4F4F6),
    divider = Color(0x0FFFFFFF),
    accent = Color(0xFFFFAD33),
    accentOn = Color(0xFF201302),
    success = Color(0xFF3FCF8E),
    warning = Color(0xFFF5C64B),
    danger = Color(0xFFF26055),
)

/**
 * Built-in accent swatches offered on the Settings screen and in the palette. Picking one keeps
 * every other token (including [MangoTheme.accentOn]) fixed — full independent control over
 * every token belongs to JSON import.
 */
val ACCENT_PRESETS: List<Pair<String, Color>> = listOf(
    "Amber" to Color(0xFFFFAD33),
    "Violet" to Color(0xFFA78BFA),
    "Sky" to Color(0xFF7FB4D9),
    "Green" to Color(0xFF3FCF8E),
    "Rose" to Color(0xFFF26E9A),
    "Red" to Color(0xFFF26055),
)

/** The type ramp: sizes, weights, and tracking, independent of any theme's colors. */
object MangoType {
    val display = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em)
    val title = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em)
    val bodyStrong = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
    val microLabel = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.12.em)
    val monoKeycap = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    val monoChapter = TextStyle(fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
}

/** Padding/gap steps on the 4-dp base grid. */
object MangoSpace {
    val base = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 28.dp
    val screenGutter = 28.dp
}

/** Corner radii, smallest to largest. */
object MangoRadius {
    val keycap = 6.dp
    val control = 10.dp
    val row = 12.dp
    val panel = 14.dp
    val large = 18.dp
}

/** Easings and named durations shared by every transition in the app; nothing bounces, nothing exceeds 320 ms. */
object MangoMotion {
    val decel = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val standard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    const val HOVER_MS = 120
    const val PRESS_MS = 100
    const val PRESS_SCALE = 0.97f
    const val SIDEBAR_OPEN_MS = 240
    const val SIDEBAR_CLOSE_MS = 200
    const val SIDEBAR_STAGGER_MS = 20
    const val SIDEBAR_STAGGER_CAP_MS = 60
    const val PALETTE_OPEN_MS = 180
    const val PALETTE_BACKDROP_MS = 240
    const val READER_OVERLAY_IN_MS = 160
    const val READER_OVERLAY_OUT_MS = 320
    const val READER_IDLE_MS = 1500
    const val VIEW_CHANGE_MS = 200
    val VIEW_CHANGE_RISE = 8.dp
    const val COVER_HOVER_MS = 160
    const val COVER_HOVER_SCALE = 1.03f
    const val PROGRESS_BAR_MS = 300
    const val BANNER_IN_MS = 200
}

/** The active [MangoTheme] for the current composition; defaults to [MangoDark] outside [ProvideMangoTheme]. */
val LocalMangoTheme = staticCompositionLocalOf { MangoDark }

/**
 * Provides [theme] to [LocalMangoTheme] for [content] and maps it onto a Material [ColorScheme]
 * so Material components keep rendering correctly. Screens should prefer [LocalMangoTheme]
 * directly; the Material mapping exists only because Material3 components need one.
 */
@Composable
fun ProvideMangoTheme(theme: MangoTheme, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMangoTheme provides theme) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                background = theme.bg0,
                surface = theme.bg1,
                surfaceVariant = theme.bg2,
                primary = theme.accent,
                onPrimary = theme.accentOn,
                error = theme.danger,
                onError = theme.accentOn,
                onBackground = theme.textPrimary,
                onSurface = theme.textPrimary,
                onSurfaceVariant = theme.textSecondary,
                outline = theme.divider,
                scrim = theme.bg0,
                // Pinned to the surface color so Material tonal elevation is a no-op: depth
                // comes from stepping the bg tokens, never from tinting toward the accent.
                surfaceTint = theme.bg1,
            ),
            content = content,
        )
    }
}
