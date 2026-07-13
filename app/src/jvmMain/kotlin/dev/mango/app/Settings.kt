package dev.mango.app

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One past search: the query text and when it ran, epoch millis. */
@Serializable
data class SearchHistoryEntry(val query: String, val at: Long)

private val searchHistorySerializer = ListSerializer(SearchHistoryEntry.serializer())

/**
 * Tiny persisted key-value settings over [Properties] at `<dataDir>/settings.properties`.
 * Loaded once into memory at construction; each setter persists immediately.
 *
 * Missing or malformed files fall back to defaults rather than crashing startup: a settings
 * file is a convenience, not a trust boundary worth failing the app over.
 */
class Settings(dataDir: Path) {
    private val log = Logger.getLogger(Settings::class.java.name)
    private val file: Path = dataDir.resolve("settings.properties")
    private val props: Properties = Properties().apply {
        try {
            if (Files.exists(file)) {
                Files.newBufferedReader(file).use { load(it) }
            }
        } catch (e: Exception) {
            log.log(Level.WARNING, "failed to load settings from $file — using defaults", e)
            clear()
        }
    }

    // dp/sec for the reader's A-key auto-scroll. A malformed value falls back to the default,
    // same stance as the file-load fallback above.
    var autoScrollSpeed: Float
        get() = props.getProperty("autoScrollSpeed")?.toFloatOrNull() ?: 120f
        set(value) {
            props.setProperty("autoScrollSpeed", value.toString())
            save()
        }

    // "grid" or "list". Any other stored value (a future format, hand-edited file) falls back
    // to the default rather than propagating an unrecognized mode into the UI.
    var libraryView: String
        get() = props.getProperty("libraryView")?.takeIf { it == "grid" || it == "list" } ?: "grid"
        set(value) {
            props.setProperty("libraryView", value)
            save()
        }

    // dp width of the reader's centered reading column. Same malformed-falls-back-to-default
    // stance as autoScrollSpeed above.
    var stripWidth: Float
        get() = props.getProperty("stripWidth")?.toFloatOrNull() ?: 880f
        set(value) {
            props.setProperty("stripWidth", value.toString())
            save()
        }

    // Whether the reader blanks the mouse cursor together with the controls overlay. Same
    // malformed-falls-back-to-default stance as the other settings above.
    var hideCursorInReader: Boolean
        get() = props.getProperty("hideCursorInReader")?.toBooleanStrictOrNull() ?: true
        set(value) {
            props.setProperty("hideCursorInReader", value.toString())
            save()
        }

    // The interface font family, by system name; null/absent means the platform default. A
    // name for a font that's no longer installed is preserved rather than cleared — see
    // resolveAppFontFamily's KDoc for the fallback behavior.
    var fontFamilyName: String?
        get() = props.getProperty("fontFamily")?.takeIf { it.isNotBlank() }
        set(value) {
            if (value.isNullOrBlank()) {
                props.remove("fontFamily")
            } else {
                props.setProperty("fontFamily", value)
            }
            save()
        }

    // Epoch millis of the last completed library-wide update check; null means never checked.
    // Same nullable-property pattern as fontFamilyName above.
    var libraryCheckedAt: Long?
        get() = props.getProperty("libraryCheckedAt")?.toLongOrNull()
        set(value) {
            if (value == null) {
                props.remove("libraryCheckedAt")
            } else {
                props.setProperty("libraryCheckedAt", value.toString())
            }
            save()
        }

    // Recent search queries, newest first. Same fallback stance as every property above: absent
    // or malformed JSON reads as no history rather than failing. Setting an empty list removes
    // the key, matching the nullable properties' clear-on-empty behavior.
    var searchHistory: List<SearchHistoryEntry>
        get() {
            val json = props.getProperty("searchHistory") ?: return emptyList()
            return try {
                Json.decodeFromString(searchHistorySerializer, json)
            } catch (_: SerializationException) {
                emptyList()
            } catch (_: IllegalArgumentException) {
                emptyList()
            }
        }
        set(value) {
            if (value.isEmpty()) {
                props.remove("searchHistory")
            } else {
                props.setProperty("searchHistory", Json.encodeToString(searchHistorySerializer, value))
            }
            save()
        }

    private fun save() {
        try {
            Files.createDirectories(file.parent)
            Files.newBufferedWriter(file).use { props.store(it, "mango settings") }
        } catch (e: IOException) {
            log.log(Level.WARNING, "failed to save settings to $file", e)
        }
    }
}
