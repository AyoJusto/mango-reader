package dev.mango.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val VALID_COLORS = mapOf(
    "bg0" to "#0D0D0F",
    "bg1" to "#141417",
    "bg2" to "#1B1B1F",
    "surface" to "#232328",
    "overlay" to "#DB18181C",
    "textPrimary" to "#F4F4F6",
    "textSecondary" to "#A3F4F4F6",
    "textTertiary" to "#66F4F4F6",
    "divider" to "#0FFFFFFF",
    "accent" to "#FFAD33",
    "accentOn" to "#201302",
    "success" to "#3FCF8E",
    "warning" to "#F5C64B",
    "danger" to "#F26055",
)

private fun themeJson(
    schema: Int = 1,
    name: String = "Mango Dark",
    colors: Map<String, String> = VALID_COLORS
): String {
    val colorsJson = colors.entries.joinToString(",") { (key, value) -> "\"$key\":\"$value\"" }
    return "{\"schema\":$schema,\"name\":\"$name\",\"colors\":{$colorsJson}}"
}

class ThemeJsonTest {
    @Test
    fun roundTripsTheDefaultTheme() {
        val result = ThemeJson.decode(ThemeJson.encode(MangoDark))

        val ok = assertIs<ThemeResult.Ok>(result)
        assertEquals(MangoDark, ok.theme)
    }

    @Test
    fun roundTripsANonDefaultAccent() {
        val violet = ACCENT_PRESETS.first { it.first == "Violet" }.second
        val theme = MangoDark.copy(accent = violet)

        val result = ThemeJson.decode(ThemeJson.encode(theme))

        val ok = assertIs<ThemeResult.Ok>(result)
        assertEquals(theme, ok.theme)
    }

    @Test
    fun invalidJsonIsAnErrorNotAThrow() {
        assertIs<ThemeResult.Error>(ThemeJson.decode("not json at all"))
    }

    @Test
    fun unsupportedSchemaIsAnError() {
        assertIs<ThemeResult.Error>(ThemeJson.decode(themeJson(schema = 2)))
    }

    @Test
    fun missingTokenIsAnError() {
        assertIs<ThemeResult.Error>(ThemeJson.decode(themeJson(colors = VALID_COLORS - "bg0")))
    }

    @Test
    fun unknownTokenIsAnError() {
        assertIs<ThemeResult.Error>(ThemeJson.decode(themeJson(colors = VALID_COLORS + ("bogus" to "#FFFFFF"))))
    }

    @Test
    fun unparsableHexValueIsAnError() {
        assertIs<ThemeResult.Error>(ThemeJson.decode(themeJson(colors = VALID_COLORS + ("bg0" to "notahex"))))
    }

    @Test
    fun themeStoreFallsBackToMangoDarkWhenFileIsMissing() {
        val dataDir = Files.createTempDirectory("theme-store-test")

        val theme = ThemeStore(dataDir).load()

        assertEquals(MangoDark, theme)
    }

    @Test
    fun listReturnsOnlyMangoDarkWhenNoThemesAreImported() {
        val dataDir = Files.createTempDirectory("theme-store-test")

        assertEquals(listOf(MangoDark), ThemeStore(dataDir).list())
    }

    @Test
    fun saveImportedThenListRoundTripsTheTheme() {
        val store = ThemeStore(Files.createTempDirectory("theme-store-test"))
        val custom = MangoDark.copy(name = "Custom Theme")

        val error = store.saveImported(custom)

        assertEquals(null, error)
        assertEquals(listOf(MangoDark, custom), store.list())
    }

    @Test
    fun listSortsImportedThemesByName() {
        val store = ThemeStore(Files.createTempDirectory("theme-store-test"))
        store.saveImported(MangoDark.copy(name = "Zeta"))
        store.saveImported(MangoDark.copy(name = "Alpha"))

        assertEquals(
            listOf(MangoDark, MangoDark.copy(name = "Alpha"), MangoDark.copy(name = "Zeta")),
            store.list(),
        )
    }

    @Test
    fun sameNameReImportOverwritesTheExistingEntry() {
        val store = ThemeStore(Files.createTempDirectory("theme-store-test"))
        val violet = ACCENT_PRESETS.first { it.first == "Violet" }.second
        store.saveImported(MangoDark.copy(name = "Custom"))

        store.saveImported(MangoDark.copy(name = "Custom", accent = violet))

        val list = store.list()
        assertEquals(listOf(MangoDark, MangoDark.copy(name = "Custom", accent = violet)), list)
    }

    @Test
    fun reservedNameIsAnErrorAndWritesNothing() {
        val store = ThemeStore(Files.createTempDirectory("theme-store-test"))

        val error = store.saveImported(MangoDark)

        assertEquals("\"Mango Dark\" is a reserved name", error)
        assertEquals(listOf(MangoDark), store.list())
    }

    @Test
    fun reservedNameIsCaseInsensitive() {
        val store = ThemeStore(Files.createTempDirectory("theme-store-test"))

        val error = store.saveImported(MangoDark.copy(name = "mango dark"))

        assertEquals("\"Mango Dark\" is a reserved name", error)
        assertEquals(listOf(MangoDark), store.list())
    }

    @Test
    fun distinctNamesThatSanitizeToTheSameFileNameRejectTheSecondImport() {
        val store = ThemeStore(Files.createTempDirectory("theme-store-test"))
        val violet = ACCENT_PRESETS.first { it.first == "Violet" }.second
        store.saveImported(MangoDark.copy(name = "Git.gud"))

        val error = store.saveImported(MangoDark.copy(name = "Git/gud", accent = violet))

        assertTrue(error != null)
        val list = store.list()
        assertEquals(listOf(MangoDark, MangoDark.copy(name = "Git.gud")), list)
    }

    @Test
    fun windowsReservedDeviceNameIsAnErrorAndWritesNothing() {
        val dataDir = Files.createTempDirectory("theme-store-test")
        val store = ThemeStore(dataDir)

        val error = store.saveImported(MangoDark.copy(name = "NUL"))

        assertTrue(error != null)
        assertEquals(listOf(MangoDark), store.list())
        assertTrue(Files.notExists(dataDir.resolve("themes")))
    }

    @Test
    fun whitespaceOnlyNameIsAnErrorAndWritesNothing() {
        val dataDir = Files.createTempDirectory("theme-store-test")
        val store = ThemeStore(dataDir)

        val error = store.saveImported(MangoDark.copy(name = "   "))

        assertTrue(error != null)
        assertEquals(listOf(MangoDark), store.list())
        assertTrue(Files.notExists(dataDir.resolve("themes")))
    }

    @Test
    fun pathTraversalNameSanitizesToAPlainFileNameInsideThemesDirectory() {
        val dataDir = Files.createTempDirectory("theme-store-test")
        val store = ThemeStore(dataDir)

        val error = store.saveImported(MangoDark.copy(name = "../evil"))

        assertEquals(null, error)
        assertTrue(Files.exists(dataDir.resolve("themes").resolve("evil.json")))
        assertTrue(Files.notExists(dataDir.resolve("evil.json")))
    }

    @Test
    fun deleteImportedRemovesTheEntry() {
        val store = ThemeStore(Files.createTempDirectory("theme-store-test"))
        store.saveImported(MangoDark.copy(name = "Custom"))

        store.deleteImported("Custom")

        assertEquals(listOf(MangoDark), store.list())
    }

    @Test
    fun deleteImportedOfAMissingNameIsANoOp() {
        val store = ThemeStore(Files.createTempDirectory("theme-store-test"))

        store.deleteImported("Nothing Here")

        assertEquals(listOf(MangoDark), store.list())
    }

    @Test
    fun malformedFileInThemesIsSkippedByList() {
        val dataDir = Files.createTempDirectory("theme-store-test")
        val themesDir = Files.createDirectories(dataDir.resolve("themes"))
        Files.writeString(themesDir.resolve("broken.json"), "not json at all")
        val store = ThemeStore(dataDir)
        store.saveImported(MangoDark.copy(name = "Good"))

        assertEquals(listOf(MangoDark, MangoDark.copy(name = "Good")), store.list())
    }
}
