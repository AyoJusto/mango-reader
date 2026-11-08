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

@Serializable
data class SearchHistoryEntry(val query: String, val at: Long)

private val searchHistorySerializer = ListSerializer(SearchHistoryEntry.serializer())

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

    var autoScrollSpeed: Float
        get() = props.getProperty("autoScrollSpeed")?.toFloatOrNull() ?: 120f
        set(value) {
            props.setProperty("autoScrollSpeed", value.toString())
            save()
        }

    var libraryView: String
        get() = props.getProperty("libraryView")?.takeIf { it == "grid" || it == "list" } ?: "grid"
        set(value) {
            props.setProperty("libraryView", value)
            save()
        }

    var stripWidth: Float
        get() = props.getProperty("stripWidth")?.toFloatOrNull() ?: 880f
        set(value) {
            props.setProperty("stripWidth", value.toString())
            save()
        }

    var hideCursorInReader: Boolean
        get() = props.getProperty("hideCursorInReader")?.toBooleanStrictOrNull() ?: true
        set(value) {
            props.setProperty("hideCursorInReader", value.toString())
            save()
        }

    // A name for an uninstalled font is preserved, not cleared, so reinstalling applies it.
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
