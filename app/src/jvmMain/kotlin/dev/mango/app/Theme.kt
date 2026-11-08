package dev.mango.app

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** All semantic colors; screens read them via LocalMangoTheme, never a hardcoded Color. */
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
    /** Derived from accent; never stored or exported. */
    val focus: Color get() = accent.copy(alpha = 0.45f)
}

/** Also the fallback when no theme file exists or it fails to load. */
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

val ACCENT_PRESETS: List<Pair<String, Color>> = listOf(
    "Amber" to Color(0xFFFFAD33),
    "Violet" to Color(0xFFA78BFA),
    "Sky" to Color(0xFF7FB4D9),
    "Green" to Color(0xFF3FCF8E),
    "Rose" to Color(0xFFF26E9A),
    "Red" to Color(0xFFF26055),
)

/** fontFamily applies to every style except the monospace ones. */
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

val LocalMangoType = staticCompositionLocalOf { MangoTypeRamp(null) }

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

object MangoSpace {
    val base = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 28.dp
    val screenGutter = 28.dp

    val contentMaxWidth = 840.dp

    val gridMaxWidth = 1320.dp
}

object MangoRadius {
    val keycap = 6.dp
    val control = 10.dp
    val row = 12.dp
    val panel = 14.dp
    val large = 18.dp

    val pill = 999.dp
}

/** With reduced set, movement is gone and every animation is a short fade. */
@Immutable
class MangoMotion(val reduced: Boolean = false) {
    val decel = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val standard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    val slides: Boolean get() = !reduced

    private fun fade(ms: Int) = if (reduced) REDUCED_FADE_MS else ms

    val hoverMs = fade(120)
    val pressMs = fade(100)
    val pressScale = if (reduced) 1f else 0.97f
    val sidebarOpenMs = fade(240)
    val sidebarCloseMs = fade(200)
    val sidebarStaggerMs = if (reduced) 0 else 20
    val sidebarStaggerCapMs = if (reduced) 0 else 60
    val paletteOpenMs = fade(180)
    val paletteBackdropMs = fade(240)
    val readerOverlayInMs = fade(160)
    val readerOverlayOutMs = fade(320)
    val readerIdleMs = 1500
    val readerPageCrossfadeMs = fade(200)
    val coverHoverMs = fade(160)
    val coverHoverScale = if (reduced) 1f else 1.03f
    val progressBarMs = fade(300)
    val bannerInMs = fade(200)
    val shimmerMs = 1800
    val screenMs = fade(240)
    val screenSlide: Dp = if (reduced) 0.dp else 48.dp
    val viewChangeMs = fade(200)
    val viewRise: Dp = if (reduced) 0.dp else 8.dp
    val pillPulseMs = fade(320)

    private companion object {
        const val REDUCED_FADE_MS = 120
    }
}

val LocalMangoMotion = staticCompositionLocalOf { MangoMotion() }

val LocalMangoTheme = staticCompositionLocalOf { MangoDark }

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
                // Pinned to surface so tonal elevation is a no-op; depth comes from the bg tokens.
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
