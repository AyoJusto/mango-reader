package dev.mango.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsTest {
    @Test
    fun malformedFileFallsBackToDefaultsWithoutThrowing() {
        val dataDir = Files.createTempDirectory("settings-test")
        val file = dataDir.resolve("settings.properties")
        Files.write(file, byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0xFE.toByte(), 0x02, 0x03))

        val settings = Settings(dataDir)

        assertEquals(120f, settings.autoScrollSpeed)
    }

    @Test
    fun autoScrollSpeedDefaultsTo120AndPersistsAcrossInstances() {
        val dataDir = Files.createTempDirectory("settings-test")
        val settings = Settings(dataDir)
        assertEquals(120f, settings.autoScrollSpeed)

        settings.autoScrollSpeed = 250f

        val reloaded = Settings(dataDir)
        assertEquals(250f, reloaded.autoScrollSpeed)
    }

    @Test
    fun stripWidthDefaultsTo880AndMalformedValueFallsBackToDefault() {
        val dataDir = Files.createTempDirectory("settings-test")
        val file = dataDir.resolve("settings.properties")
        Files.write(file, "stripWidth=not-a-number\n".toByteArray())

        val settings = Settings(dataDir)

        assertEquals(880f, settings.stripWidth)
    }

    @Test
    fun stripWidthPersistsAcrossInstances() {
        val dataDir = Files.createTempDirectory("settings-test")
        val settings = Settings(dataDir)
        assertEquals(880f, settings.stripWidth)

        settings.stripWidth = 1200f

        val reloaded = Settings(dataDir)
        assertEquals(1200f, reloaded.stripWidth)
    }

    @Test
    fun hideCursorInReaderDefaultsToTrueAndMalformedValueFallsBackToDefault() {
        val dataDir = Files.createTempDirectory("settings-test")
        val file = dataDir.resolve("settings.properties")
        Files.write(file, "hideCursorInReader=not-a-boolean\n".toByteArray())

        val settings = Settings(dataDir)

        assertEquals(true, settings.hideCursorInReader)
    }

    @Test
    fun hideCursorInReaderRoundTripsAcrossInstances() {
        val dataDir = Files.createTempDirectory("settings-test")
        val settings = Settings(dataDir)
        assertEquals(true, settings.hideCursorInReader)

        settings.hideCursorInReader = false

        val reloaded = Settings(dataDir)
        assertEquals(false, reloaded.hideCursorInReader)
    }

    @Test
    fun fontFamilyNameDefaultsToNullAndRoundTripsAcrossInstances() {
        val dataDir = Files.createTempDirectory("settings-test")
        val settings = Settings(dataDir)
        assertEquals(null, settings.fontFamilyName)

        settings.fontFamilyName = "Georgia"

        val reloaded = Settings(dataDir)
        assertEquals("Georgia", reloaded.fontFamilyName)
    }

    @Test
    fun fontFamilyNameSetToNullClearsThePersistedValueAndBlankReadsAsNull() {
        val dataDir = Files.createTempDirectory("settings-test")
        val file = dataDir.resolve("settings.properties")
        Files.write(file, "fontFamily=\n".toByteArray())

        val settings = Settings(dataDir)
        assertEquals(null, settings.fontFamilyName)

        settings.fontFamilyName = "Georgia"
        settings.fontFamilyName = null

        val reloaded = Settings(dataDir)
        assertEquals(null, reloaded.fontFamilyName)
    }

    @Test
    fun libraryCheckedAtDefaultsToNullAndRoundTripsAcrossInstances() {
        val dataDir = Files.createTempDirectory("settings-test")
        val settings = Settings(dataDir)
        assertEquals(null, settings.libraryCheckedAt)

        settings.libraryCheckedAt = 1_700_000_000_000L

        val reloaded = Settings(dataDir)
        assertEquals(1_700_000_000_000L, reloaded.libraryCheckedAt)
    }

    @Test
    fun libraryCheckedAtSetToNullClearsThePersistedValueAndMalformedValueReadsAsNull() {
        val dataDir = Files.createTempDirectory("settings-test")
        val file = dataDir.resolve("settings.properties")
        Files.write(file, "libraryCheckedAt=not-a-number\n".toByteArray())

        val settings = Settings(dataDir)
        assertEquals(null, settings.libraryCheckedAt)

        settings.libraryCheckedAt = 42L
        settings.libraryCheckedAt = null

        val reloaded = Settings(dataDir)
        assertEquals(null, reloaded.libraryCheckedAt)
    }

    @Test
    fun searchHistoryDefaultsToEmptyAndRoundTripsAcrossInstances() {
        val dataDir = Files.createTempDirectory("settings-test")
        val settings = Settings(dataDir)
        assertEquals(emptyList(), settings.searchHistory)

        val history = listOf(SearchHistoryEntry("solo leveling", 100L), SearchHistoryEntry("tower of god", 200L))
        settings.searchHistory = history

        val reloaded = Settings(dataDir)
        assertEquals(history, reloaded.searchHistory)
    }

    @Test
    fun searchHistoryMalformedJsonFallsBackToEmpty() {
        val dataDir = Files.createTempDirectory("settings-test")
        val file = dataDir.resolve("settings.properties")
        Files.write(file, "searchHistory=not-json\n".toByteArray())

        val settings = Settings(dataDir)

        assertEquals(emptyList(), settings.searchHistory)
    }

    @Test
    fun searchHistorySetToEmptyRemovesThePersistedValue() {
        val dataDir = Files.createTempDirectory("settings-test")
        val settings = Settings(dataDir)

        settings.searchHistory = listOf(SearchHistoryEntry("solo leveling", 100L))
        settings.searchHistory = emptyList()

        val reloaded = Settings(dataDir)
        assertEquals(emptyList(), reloaded.searchHistory)
    }
}
