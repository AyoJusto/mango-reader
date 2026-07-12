package dev.mango.app

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import org.jetbrains.skia.FontMgr

/**
 * Every installed system font family name, deduplicated and sorted for display. Uses skiko's
 * [FontMgr] rather than `java.awt.GraphicsEnvironment`, which fragments a single family into one
 * entry per weight on Windows. Enumerating 100-300 families is not free — callers should invoke
 * this once, off the UI thread, and cache the result rather than re-enumerating per composition.
 */
fun installedFontFamilies(): List<String> {
    val mgr = FontMgr.default
    return (0 until mgr.familiesCount)
        .map { mgr.getFamilyName(it) }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
}

/**
 * Resolves a stored font family [name] against [installed], the families actually available.
 * Null, blank, or a name no longer present in [installed] falls back to the platform default
 * ([FontFamily] itself gives no error for a missing family — it silently renders a fallback
 * face) — the stored value is preserved either way, so a reinstalled font applies automatically
 * without the user having to reselect it.
 */
@OptIn(ExperimentalTextApi::class)
fun resolveAppFontFamily(name: String?, installed: List<String>): FontFamily? {
    if (name.isNullOrBlank() || name !in installed) return null
    return FontFamily(name)
}
