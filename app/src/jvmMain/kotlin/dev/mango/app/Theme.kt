package dev.mango.app

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

/** Pure near-black behind reader pages; darker than surface on purpose. */
val ReaderBlack = Color(0xFF0D0C0C)

@Composable
fun MangoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DragonColors, content = content)
}
