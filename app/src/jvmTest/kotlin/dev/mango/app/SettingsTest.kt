package dev.mango.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsTest {
    @Test
    fun defaultsWhenFileMissing() {
        val dataDir = Files.createTempDirectory("settings-test")

        val settings = Settings(dataDir)

        assertEquals(Themes.DEFAULT, settings.theme)
    }

    @Test
    fun setPersistsAndASecondInstanceReadsItBack() {
        val dataDir = Files.createTempDirectory("settings-test")
        val settings = Settings(dataDir)

        settings.theme = "midnight"

        val reloaded = Settings(dataDir)
        assertEquals("midnight", reloaded.theme)
    }

    @Test
    fun malformedFileFallsBackToDefaultsWithoutThrowing() {
        val dataDir = Files.createTempDirectory("settings-test")
        val file = dataDir.resolve("settings.properties")
        Files.write(file, byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0xFE.toByte(), 0x02, 0x03))

        val settings = Settings(dataDir)

        assertEquals(Themes.DEFAULT, settings.theme)
    }

    @Test
    fun unknownThemeNameResolvesToTheDefaultScheme() {
        val scheme = Themes.scheme("not-a-real-theme")

        assertEquals(Themes.schemes.getValue(Themes.DEFAULT), scheme)
    }
}
