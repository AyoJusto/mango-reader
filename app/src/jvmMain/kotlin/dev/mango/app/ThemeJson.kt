package dev.mango.app

import androidx.compose.ui.graphics.Color
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.roundToInt
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val SCHEMA_VERSION = 1

private val TOKEN_NAMES = listOf(
    "bg0", "bg1", "bg2", "surface", "overlay",
    "textPrimary", "textSecondary", "textTertiary", "divider",
    "accent", "accentOn", "success", "warning", "danger",
)

@Serializable
private data class ThemeWire(val schema: Int, val name: String, val colors: Map<String, String>)

/** Outcome of [ThemeJson.decode]: the parsed theme, or a human-readable reason it was rejected. */
sealed class ThemeResult {
    data class Ok(val theme: MangoTheme) : ThemeResult()
    data class Error(val message: String) : ThemeResult()
}

/**
 * The theme wire format: `{"schema":1,"name":"...","colors":{"bg0":"#RRGGBB",...}}` with exactly
 * the 14 [MangoTheme] color tokens as `#RRGGBB` or `#AARRGGBB` hex strings. [decode] is a trust
 * boundary — malformed JSON, a schema mismatch, a missing or unknown token, or an unparsable hex
 * value all produce a [ThemeResult.Error]; it never throws.
 */
object ThemeJson {
    fun encode(theme: MangoTheme): String {
        val wire = ThemeWire(
            schema = SCHEMA_VERSION,
            name = theme.name,
            colors = mapOf(
                "bg0" to theme.bg0.toHex(),
                "bg1" to theme.bg1.toHex(),
                "bg2" to theme.bg2.toHex(),
                "surface" to theme.surface.toHex(),
                "overlay" to theme.overlay.toHex(),
                "textPrimary" to theme.textPrimary.toHex(),
                "textSecondary" to theme.textSecondary.toHex(),
                "textTertiary" to theme.textTertiary.toHex(),
                "divider" to theme.divider.toHex(),
                "accent" to theme.accent.toHex(),
                "accentOn" to theme.accentOn.toHex(),
                "success" to theme.success.toHex(),
                "warning" to theme.warning.toHex(),
                "danger" to theme.danger.toHex(),
            ),
        )
        return Json.encodeToString(ThemeWire.serializer(), wire)
    }

    fun decode(json: String): ThemeResult {
        val wire = try {
            Json.decodeFromString(ThemeWire.serializer(), json)
        } catch (e: SerializationException) {
            return ThemeResult.Error("malformed theme JSON: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return ThemeResult.Error("malformed theme JSON: ${e.message}")
        }

        if (wire.schema != SCHEMA_VERSION) {
            return ThemeResult.Error("unsupported theme schema ${wire.schema}, expected $SCHEMA_VERSION")
        }

        val unknown = (wire.colors.keys - TOKEN_NAMES.toSet()).sorted()
        if (unknown.isNotEmpty()) {
            return ThemeResult.Error("unknown color token(s): ${unknown.joinToString(", ")}")
        }
        val missing = (TOKEN_NAMES.toSet() - wire.colors.keys).sorted()
        if (missing.isNotEmpty()) {
            return ThemeResult.Error("missing color token(s): ${missing.joinToString(", ")}")
        }

        val colors = mutableMapOf<String, Color>()
        for (tokenName in TOKEN_NAMES) {
            val hex = wire.colors.getValue(tokenName)
            val color = parseHex(hex) ?: return ThemeResult.Error("invalid color value for \"$tokenName\": \"$hex\"")
            colors[tokenName] = color
        }

        return ThemeResult.Ok(
            MangoTheme(
                name = wire.name,
                bg0 = colors.getValue("bg0"),
                bg1 = colors.getValue("bg1"),
                bg2 = colors.getValue("bg2"),
                surface = colors.getValue("surface"),
                overlay = colors.getValue("overlay"),
                textPrimary = colors.getValue("textPrimary"),
                textSecondary = colors.getValue("textSecondary"),
                textTertiary = colors.getValue("textTertiary"),
                divider = colors.getValue("divider"),
                accent = colors.getValue("accent"),
                accentOn = colors.getValue("accentOn"),
                success = colors.getValue("success"),
                warning = colors.getValue("warning"),
                danger = colors.getValue("danger"),
            ),
        )
    }
}

private fun Color.toHex(): String {
    val a = (alpha * 255f).roundToInt()
    val r = (red * 255f).roundToInt()
    val g = (green * 255f).roundToInt()
    val b = (blue * 255f).roundToInt()
    val rgb = "${r.toHex2()}${g.toHex2()}${b.toHex2()}"
    return if (a == 255) "#$rgb" else "#${a.toHex2()}$rgb"
}

private fun Int.toHex2(): String = toString(16).padStart(2, '0').uppercase()

/** Parses `#RRGGBB` or `#AARRGGBB`; anything else (missing `#`, wrong length, non-hex digits) is null. */
private fun parseHex(hex: String): Color? {
    if (!hex.startsWith("#")) return null
    val digits = hex.substring(1)
    if (digits.length != 6 && digits.length != 8) return null
    // toLongOrNull(16) alone would accept a leading sign; only hex digits are valid here.
    if (!digits.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
    val value = digits.toLongOrNull(16) ?: return null
    return when (digits.length) {
        6 -> Color((0xFF000000L or value).toInt())
        else -> Color(value.toInt())
    }
}

/**
 * Persists the active [MangoTheme] as JSON at `<dataDir>/theme.json`. Missing or malformed files
 * fall back to [MangoDark] and log a warning — same stance as [Settings]'s loader: the theme
 * file is a convenience, not a trust boundary worth failing startup over.
 */
class ThemeStore(dataDir: Path) {
    private val log = Logger.getLogger(ThemeStore::class.java.name)
    private val file: Path = dataDir.resolve("theme.json")

    fun load(): MangoTheme {
        if (!Files.exists(file)) return MangoDark
        val json = try {
            Files.readString(file)
        } catch (e: IOException) {
            log.log(Level.WARNING, "failed to read theme from $file — using default", e)
            return MangoDark
        }
        return when (val result = ThemeJson.decode(json)) {
            is ThemeResult.Ok -> result.theme
            is ThemeResult.Error -> {
                log.log(Level.WARNING, "failed to load theme from $file — using default: ${result.message}")
                MangoDark
            }
        }
    }

    fun save(theme: MangoTheme) {
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, ThemeJson.encode(theme))
        } catch (e: IOException) {
            log.log(Level.WARNING, "failed to save theme to $file", e)
        }
    }
}
