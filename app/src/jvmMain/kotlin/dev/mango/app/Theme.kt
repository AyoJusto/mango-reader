package dev.mango.app

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Kanagawa Dragon palette — locked design tokens (M3.1)
private val DragonColors = darkColorScheme(
    primary = Color(0xFF8BA4B0),        // dragonBlue2
    onPrimary = Color(0xFF0D0C0C),
    secondary = Color(0xFF8992A7),      // dragonViolet
    onSecondary = Color(0xFF0D0C0C),
    tertiary = Color(0xFFB6927B),       // dragonOrange
    onTertiary = Color(0xFF0D0C0C),
    error = Color(0xFFC4746E),          // dragonRed
    onError = Color(0xFF0D0C0C),
    background = Color(0xFF181616),     // dragonBlack3
    onBackground = Color(0xFFC5C9C5),   // dragonWhite
    surface = Color(0xFF181616),
    onSurface = Color(0xFFC5C9C5),
    surfaceVariant = Color(0xFF282727), // dragonBlack4
    onSurfaceVariant = Color(0xFFA6A69C), // dragonGray
    outline = Color(0xFF393836),        // dragonBlack5
)

// Second built-in scheme, proving the theme is actually configurable and not just DragonColors
// with an extra layer of indirection.
private val MidnightColors = darkColorScheme(
    primary = Color(0xFF7FB4D9),
    onPrimary = Color(0xFF0B0E11),
    secondary = Color(0xFF9DA7C4),
    onSecondary = Color(0xFF0B0E11),
    tertiary = Color(0xFFC9A87C),
    onTertiary = Color(0xFF0B0E11),
    error = Color(0xFFCC7A7A),
    onError = Color(0xFF0B0E11),
    background = Color(0xFF101418),
    onBackground = Color(0xFFD6DEE7),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFD6DEE7),
    surfaceVariant = Color(0xFF1B2026),
    onSurfaceVariant = Color(0xFF9AA5B1),
    outline = Color(0xFF2A3138),
)

/**
 * Pure near-black behind reader pages; darker than surface on purpose. Deliberately NOT part
 * of the [Themes] registry: the reader canvas stays theme-independent for now regardless of
 * which color scheme the user picks.
 */
val ReaderBlack = Color(0xFF0D0C0C)

/** Named color-scheme registry. Nothing outside this file may reference a palette color directly. */
object Themes {
    const val DEFAULT = "kanagawa-dragon"
    val schemes: Map<String, ColorScheme> = mapOf(
        "kanagawa-dragon" to DragonColors,
        "midnight" to MidnightColors,
    )

    fun scheme(name: String): ColorScheme = schemes[name] ?: schemes.getValue(DEFAULT)
}

@Composable
fun MangoTheme(themeName: String = Themes.DEFAULT, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Themes.scheme(themeName), content = content)
}
