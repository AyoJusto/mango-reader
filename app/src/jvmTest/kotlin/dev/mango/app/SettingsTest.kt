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
}
