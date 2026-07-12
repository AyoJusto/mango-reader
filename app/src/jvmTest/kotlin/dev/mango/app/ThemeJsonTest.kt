package dev.mango.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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

private fun themeJson(schema: Int = 1, name: String = "Mango Dark", colors: Map<String, String> = VALID_COLORS): String {
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
}
