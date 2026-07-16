package dev.mango.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.roundToInt

/**
 * Every user-facing settings entry — the single source of truth the search-everywhere palette
 * derives its Settings hits from. A new setting is not done until it is registered here; the
 * completeness tests in PaletteFlowTest/SettingsScreenTest fail otherwise.
 */
val SETTINGS_ENTRIES = listOf(
    "Theme",
    "Remove theme",
    "Accent",
    "App font",
    "Export theme",
    "Import theme",
    "Strip width",
    "Auto-scroll speed",
    "Hide cursor",
    "Library view",
    "Shortcuts",
)

private val settingsLog = Logger.getLogger("SettingsScreen")

/** The App font dropdown's first option: no stored family, platform default rendering. */
private const val FONT_SYSTEM_DEFAULT = "System default"

/**
 * Test hook prefix: each row/control that backs a [SETTINGS_ENTRIES] title carries the tag
 * `"settings-entry-<title>"`, so the completeness tests can assert a real control exists for
 * every registered entry without depending on exact button/label wording.
 */
internal fun settingsEntryTag(title: String): String = "settings-entry-$title"

/**
 * Pure, data-driven content — the screenshot harness renders this directly. No stateful loader
 * needed: the current theme and the change callback are hoisted all the way up to Main.kt so a
 * theme or accent pick applies live without a restart. Same pattern for the auto-scroll speed,
 * strip width, and hide-cursor toggle.
 */
@Composable
fun SettingsScreenContent(
    theme: MangoTheme,
    onThemeChange: (MangoTheme) -> Unit,
    themeLibrary: List<MangoTheme> = listOf(MangoDark),
    onThemeImport: (MangoTheme) -> String? = { null },
    onThemeDelete: () -> Unit = {},
    autoScrollSpeed: Float = 120f,
    onAutoScrollSpeedChange: (Float) -> Unit = {},
    stripWidth: Float = 880f,
    onStripWidthChange: (Float) -> Unit = {},
    hideCursorInReader: Boolean = true,
    onHideCursorInReaderChange: (Boolean) -> Unit = {},
    libraryView: String = LIBRARY_VIEW_GRID,
    onLibraryViewChange: (String) -> Unit = {},
    fontFamilyName: String? = null,
    installedFonts: List<String> = emptyList(),
    onFontFamilyChange: (String?) -> Unit = {},
) {
    var importError by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = theme.bg0) {
        ContentColumn(max = MangoSpace.contentMaxWidth) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(MangoSpace.screenGutter),
                verticalArrangement = Arrangement.spacedBy(MangoSpace.xl),
            ) {
                Text(text = "Settings", style = MangoType.display, color = theme.textPrimary)

                SettingsGroup(label = "Appearance") {
                    SettingsRow(
                        title = "Theme",
                        subtitle = "${theme.name} · yours to edit",
                    ) {
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(MangoSpace.xs)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(MangoSpace.xs),
                            ) {
                                // A stored theme no longer in the library (deleted from under it)
                                // shows as the library's first entry rather than a stale name the
                                // dropdown can't honor — same stance as the App font dropdown.
                                val options = themeLibrary.map { it.name }
                                val shownThemeName = theme.name.takeIf { it in options } ?: options.first()
                                KitDropdown(
                                    selected = shownThemeName,
                                    options = options,
                                    onSelect = { picked ->
                                        themeLibrary.firstOrNull { it.name == picked }?.let(onThemeChange)
                                    },
                                    modifier = Modifier.testTag(settingsEntryTag("Theme")),
                                )
                                KitButton(
                                    label = "Remove",
                                    style = KitButtonStyle.DANGER,
                                    enabled = theme.name != MangoDark.name,
                                    modifier = Modifier.testTag(settingsEntryTag("Remove theme")),
                                    onClick = onThemeDelete,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(MangoSpace.xs)) {
                                KitButton(
                                    label = "Export .json",
                                    style = KitButtonStyle.SECONDARY,
                                    modifier = Modifier.testTag(settingsEntryTag("Export theme")),
                                    onClick = {
                                        val chooser = JFileChooser().apply {
                                            fileFilter = FileNameExtensionFilter("Theme (*.json)", "json")
                                            selectedFile = File("${theme.name}.json")
                                        }
                                        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                                            var target = chooser.selectedFile
                                            if (!target.name.endsWith(".json")) {
                                                target = File(target.parentFile, "${target.name}.json")
                                            }
                                            try {
                                                target.writeText(ThemeJson.encode(theme))
                                            } catch (e: IOException) {
                                                settingsLog.log(Level.WARNING, "failed to export theme to $target", e)
                                            }
                                        }
                                    },
                                )
                                KitButton(
                                    label = "Import…",
                                    style = KitButtonStyle.GHOST,
                                    modifier = Modifier.testTag(settingsEntryTag("Import theme")),
                                    onClick = {
                                        val chooser = JFileChooser().apply {
                                            fileFilter = FileNameExtensionFilter("Theme (*.json)", "json")
                                        }
                                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                            val outcome = try {
                                                ThemeJson.decode(chooser.selectedFile.readText())
                                            } catch (e: IOException) {
                                                ThemeResult.Error("failed to read ${chooser.selectedFile.name}: ${e.message}")
                                            }
                                            when (outcome) {
                                                is ThemeResult.Ok -> importError = onThemeImport(outcome.theme)
                                                is ThemeResult.Error -> importError = outcome.message
                                            }
                                        }
                                    },
                                )
                            }
                            importError?.let { message ->
                                Text(
                                    text = message,
                                    style = MangoType.caption,
                                    color = theme.danger,
                                    modifier = Modifier.padding(top = MangoSpace.base),
                                )
                            }
                        }
                    }
                    SettingsDivider()
                    SettingsRow(title = "Accent", subtitle = "Recolors every accent token instantly") {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(MangoSpace.xs),
                            modifier = Modifier.testTag(settingsEntryTag("Accent")),
                        ) {
                            ACCENT_PRESETS.forEach { (label, color) ->
                                AccentSwatch(
                                    label = label,
                                    color = color,
                                    selected = color == theme.accent,
                                    onClick = { onThemeChange(theme.copy(accent = color)) },
                                )
                            }
                        }
                    }
                    SettingsDivider()
                    SettingsRow(
                        title = "App font",
                        subtitle = "Applies to the interface, not the pages",
                        modifier = Modifier.testTag(settingsEntryTag("App font")),
                    ) {
                        // A stored name whose font is gone shows as the default rather than
                        // rendering a stale label the dropdown can't honor.
                        val shown = fontFamilyName?.takeIf { it in installedFonts } ?: FONT_SYSTEM_DEFAULT
                        KitDropdown(
                            selected = shown,
                            options = listOf(FONT_SYSTEM_DEFAULT) + installedFonts,
                            onSelect = { picked ->
                                onFontFamilyChange(picked.takeIf { it != FONT_SYSTEM_DEFAULT })
                            },
                        )
                    }
                }

                SettingsGroup(label = "Reader") {
                    var pendingStripWidth by remember(stripWidth) { mutableStateOf(stripWidth) }
                    SettingsRow(title = "Strip width", subtitle = "Width of the centered reading column") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm)
                        ) {
                            Slider(
                                value = pendingStripWidth,
                                onValueChange = { pendingStripWidth = it },
                                onValueChangeFinished = { onStripWidthChange(pendingStripWidth) },
                                valueRange = 600f..1400f,
                                modifier = Modifier.width(180.dp).testTag(settingsEntryTag("Strip width")),
                            )
                            Text(
                                text = pendingStripWidth.roundToInt().toString(),
                                style = MangoType.monoChapter,
                                color = theme.textPrimary,
                                modifier = Modifier.width(36.dp),
                            )
                        }
                    }
                    SettingsDivider()
                    var pendingAutoScroll by remember(autoScrollSpeed) { mutableStateOf(autoScrollSpeed) }
                    SettingsRow(title = "Auto-scroll speed", subtitle = "dp/s while auto-scroll (A) is running") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm)
                        ) {
                            Slider(
                                value = pendingAutoScroll,
                                onValueChange = { pendingAutoScroll = it },
                                onValueChangeFinished = { onAutoScrollSpeedChange(pendingAutoScroll) },
                                valueRange = 30f..600f,
                                modifier = Modifier.width(180.dp).testTag(settingsEntryTag("Auto-scroll speed")),
                            )
                            Text(
                                text = pendingAutoScroll.roundToInt().toString(),
                                style = MangoType.monoChapter,
                                color = theme.textPrimary,
                                modifier = Modifier.width(36.dp),
                            )
                        }
                    }
                    SettingsDivider()
                    SettingsRow(title = "Hide cursor", subtitle = "Blank the mouse cursor with the reader overlay") {
                        TogglePill(
                            checked = hideCursorInReader,
                            onCheckedChange = onHideCursorInReaderChange,
                            modifier = Modifier.testTag(settingsEntryTag("Hide cursor")),
                        )
                    }
                }

                SettingsGroup(label = "General") {
                    SettingsRow(title = "Library view") {
                        SegmentedControl(
                            options = listOf("Grid", "List"),
                            selectedIndex = if (libraryView == LIBRARY_VIEW_LIST) 1 else 0,
                            onSelect = { index -> onLibraryViewChange(if (index == 0) LIBRARY_VIEW_GRID else LIBRARY_VIEW_LIST) },
                            modifier = Modifier.testTag(settingsEntryTag("Library view")),
                        )
                    }
                    SettingsDivider()
                    SettingsRow(title = "Shortcuts") {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(MangoSpace.base + 2.dp),
                            modifier = Modifier.testTag(settingsEntryTag("Shortcuts")),
                        ) {
                            ShortcutLine(label = "Command palette", keys = listOf("shift", "shift"))
                            ShortcutLine(label = "Toggle sidebar", keys = listOf("ctrl", "s"))
                        }
                    }
                }
            }
        }
    }
}

/** A micro-label group header over a bg1 card; rows inside supply their own [SettingsDivider]s. */
@Composable
private fun SettingsGroup(label: String, content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalMangoTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(MangoSpace.sm)) {
        Text(text = label.uppercase(), style = MangoType.microLabel, color = theme.textTertiary)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MangoRadius.panel))
                .background(theme.bg1),
            content = content,
        )
    }
}

/** One settings row: title + sub on the left, an arbitrary control on the right. */
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    control: @Composable () -> Unit,
) {
    val theme = LocalMangoTheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp, horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = theme.textPrimary)
            if (subtitle != null) {
                Text(text = subtitle, style = MangoType.caption, color = theme.textTertiary)
            }
        }
        control()
    }
}

/** A hairline divider between two rows of the same group, inset from both edges — never around a group. */
@Composable
private fun SettingsDivider() {
    val theme = LocalMangoTheme.current
    Box(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(theme.divider),
    )
}

/** One accent color swatch; the selected swatch gets an accent ring offset by a bg gap. */
@Composable
private fun AccentSwatch(label: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalMangoTheme.current
    // Fixed footprint at ring size: the selection ring must not change the swatch's outer
    // bounds, or the row jumps vertically and neighbors reflow when the selection moves.
    Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .border(2.dp, theme.accent, CircleShape),
            )
        }
        Box(
            modifier = Modifier
                .testTag("accent-swatch-$label")
                .size(26.dp)
                .clip(CircleShape)
                .background(color)
                .clickable(onClick = onClick),
        )
    }
}

/** A label and its keycaps, right-aligned — one line of the display-only Shortcuts row. */
@Composable
private fun ShortcutLine(label: String, keys: List<String>) {
    val theme = LocalMangoTheme.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MangoSpace.xs)) {
        Text(text = label, style = MangoType.caption, color = theme.textSecondary)
        keys.forEach { key -> Keycap(key) }
    }
}

private val TOGGLE_PILL_SIZE = 38.dp to 22.dp
private val TOGGLE_THUMB_SIZE = 18.dp
private val TOGGLE_THUMB_INSET = 2.dp

/**
 * A 38x22 toggle pill: accent track when checked, a thumb that slides between the two edges.
 * File-private — not a Kit component, this is the one place the app needs it.
 */
@Composable
private fun TogglePill(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalMangoTheme.current
    val trackColor by animateColorAsState(
        targetValue = if (checked) theme.accent else theme.surface,
        animationSpec = tween(MangoMotion.HOVER_MS),
    )
    val thumbStart by animateDpAsState(
        targetValue = if (checked) {
            TOGGLE_PILL_SIZE.first - TOGGLE_THUMB_SIZE - TOGGLE_THUMB_INSET
        } else {
            TOGGLE_THUMB_INSET
        },
        animationSpec = tween(MangoMotion.COVER_HOVER_MS, easing = MangoMotion.decel),
    )
    Box(
        modifier = modifier
            .size(width = TOGGLE_PILL_SIZE.first, height = TOGGLE_PILL_SIZE.second)
            .clip(RoundedCornerShape(MangoRadius.pill))
            .background(trackColor)
            .clickable { onCheckedChange(!checked) },
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbStart, top = TOGGLE_THUMB_INSET)
                .size(TOGGLE_THUMB_SIZE)
                .clip(CircleShape)
                .background(theme.textPrimary),
        )
    }
}
