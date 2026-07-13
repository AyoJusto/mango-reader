package dev.mango.app

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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

/**
 * The type ramp: sizes, weights, and tracking, independent of any theme's colors. [fontFamily]
 * applies to every style except [monoKeycap] and [monoChapter], which stay monospace regardless
 * of the chosen interface font (they render literal key names and chapter numbers, not prose).
 */
class MangoTypeRamp(fontFamily: FontFamily?) {
    val display = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.02).em,
        fontFamily = fontFamily
    )
    val title = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.01).em,
        fontFamily = fontFamily
    )
    val bodyStrong =
        TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = fontFamily)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal, fontFamily = fontFamily)
    val label = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, fontFamily = fontFamily)
    val caption =
        TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal, fontFamily = fontFamily)
    val meta = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Normal, fontFamily = fontFamily)
    val hint = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, fontFamily = fontFamily)
    val microLabel =
        TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.12.em, fontFamily = fontFamily)
    val monoKeycap = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    val monoChapter = TextStyle(fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
}

/** The active [MangoTypeRamp] for the current composition; defaults to the platform font outside [ProvideMangoTheme]. */
val LocalMangoType = staticCompositionLocalOf { MangoTypeRamp(null) }

/**
 * Facade over [LocalMangoType] so every existing `MangoType.foo` call site keeps compiling and
 * reading the app's current font choice without threading it through every screen by hand.
 */
object MangoType {
    val display: TextStyle @Composable get() = LocalMangoType.current.display
    val title: TextStyle @Composable get() = LocalMangoType.current.title
    val bodyStrong: TextStyle @Composable get() = LocalMangoType.current.bodyStrong
    val body: TextStyle @Composable get() = LocalMangoType.current.body
    val label: TextStyle @Composable get() = LocalMangoType.current.label
    val caption: TextStyle @Composable get() = LocalMangoType.current.caption
    val meta: TextStyle @Composable get() = LocalMangoType.current.meta
    val hint: TextStyle @Composable get() = LocalMangoType.current.hint
    val microLabel: TextStyle @Composable get() = LocalMangoType.current.microLabel
    val monoKeycap: TextStyle @Composable get() = LocalMangoType.current.monoKeycap
    val monoChapter: TextStyle @Composable get() = LocalMangoType.current.monoChapter
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

    /** Width ceiling for reading/form screens (settings, lists, details) — content centers beyond it. */
    val contentMaxWidth = 840.dp

    /** Width ceiling for cover-grid screens; grids stop adding columns past this and center. */
    val gridMaxWidth = 1320.dp
}

/** Corner radii, smallest to largest. */
object MangoRadius {
    val keycap = 6.dp
    val control = 10.dp
    val row = 12.dp
    val panel = 14.dp
    val large = 18.dp

    /** Large enough to round any control height into a full stadium/circle shape. */
    val pill = 999.dp
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
    const val READER_PAGE_CROSSFADE_MS = 200
    const val COVER_HOVER_MS = 160
    const val COVER_HOVER_SCALE = 1.03f
    const val PROGRESS_BAR_MS = 300
    const val BANNER_IN_MS = 200
    const val SHIMMER_MS = 1800
}

/** The active [MangoTheme] for the current composition; defaults to [MangoDark] outside [ProvideMangoTheme]. */
val LocalMangoTheme = staticCompositionLocalOf { MangoDark }

/**
 * Provides [theme] to [LocalMangoTheme] for [content] and maps it onto a Material
 * [androidx.compose.material3.ColorScheme] so Material components keep rendering correctly.
 * Screens should prefer [LocalMangoTheme] directly; the Material mapping exists only because
 * Material3 components need one. [fontFamily] becomes the interface font (via [LocalMangoType])
 * and the ambient text style, so both [MangoType]-styled and raw-`fontSize` `Text` calls inherit
 * it; null keeps the platform default.
 */
@Composable
fun ProvideMangoTheme(theme: MangoTheme, fontFamily: FontFamily? = null, content: @Composable () -> Unit) {
    val typeRamp = remember(fontFamily) { MangoTypeRamp(fontFamily) }
    CompositionLocalProvider(LocalMangoTheme provides theme, LocalMangoType provides typeRamp) {
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
        ) {
            if (fontFamily != null) {
                ProvideTextStyle(LocalTextStyle.current.copy(fontFamily = fontFamily), content)
            } else {
                content()
            }
        }
    }
}
