package dev.mango.app

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import org.jetbrains.skia.FontMgr

/** Not GraphicsEnvironment, which splits one family per weight on Windows; call once, off the UI thread. */
fun installedFontFamilies(): List<String> {
    val mgr = FontMgr.default
    return (0 until mgr.familiesCount)
        .map { mgr.getFamilyName(it) }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
}

/** Falls back to the platform default for null, blank or uninstalled names; the stored value is kept. */
@OptIn(ExperimentalTextApi::class)
fun resolveAppFontFamily(name: String?, installed: List<String>): FontFamily? {
    if (name.isNullOrBlank() || name !in installed) return null
    return FontFamily(name)
}
