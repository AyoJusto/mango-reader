package dev.mango.app

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Tiny persisted key-value settings over [Properties] at `<dataDir>/settings.properties`.
 * Loaded once into memory at construction; each setter persists immediately. Backs the
 * Settings screen (M4.4a) — [theme] is read on startup and written back on every pick.
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

    var theme: String
        get() = props.getProperty("theme") ?: Themes.DEFAULT
        set(value) {
            props.setProperty("theme", value)
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
