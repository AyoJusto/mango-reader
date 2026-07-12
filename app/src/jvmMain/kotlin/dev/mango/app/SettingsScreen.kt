package dev.mango.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
val SETTINGS_ENTRIES = listOf("Theme", "Accent", "Export theme", "Import theme", "Auto-scroll speed", "Library view")

private val settingsLog = Logger.getLogger("SettingsScreen")

/**
 * Test hook prefix: each row/control that backs a [SETTINGS_ENTRIES] title carries the tag
 * `"settings-entry-<title>"`, so the completeness tests can assert a real control exists for
 * every registered entry without depending on exact button/label wording.
 */
internal fun settingsEntryTag(title: String): String = "settings-entry-$title"

/**
 * Pure, data-driven content — the screenshot harness renders this directly. No stateful loader
 * needed: the current theme and the change callback are hoisted all the way up to Main.kt so a
 * theme or accent pick applies live without a restart. Same pattern for the auto-scroll speed.
 */
@Composable
fun SettingsScreenContent(
    theme: MangoTheme,
    onThemeChange: (MangoTheme) -> Unit,
    autoScrollSpeed: Float = 120f,
    onAutoScrollSpeedChange: (Float) -> Unit = {},
    libraryView: String = LIBRARY_VIEW_GRID,
    onLibraryViewChange: (String) -> Unit = {},
) {
    var importError by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = theme.bg0) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Theme",
                style = MaterialTheme.typography.titleMedium,
                color = theme.textPrimary,
                modifier = Modifier.testTag(settingsEntryTag("Theme")),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = theme.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Button(
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
                ) {
                    Text("Export .json")
                }
                OutlinedButton(
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
                                is ThemeResult.Ok -> {
                                    importError = null
                                    onThemeChange(outcome.theme)
                                }
                                is ThemeResult.Error -> importError = outcome.message
                            }
                        }
                    },
                ) {
                    Text("Import…")
                }
            }
            importError?.let { message ->
                Text(text = message, style = MaterialTheme.typography.bodySmall, color = theme.danger)
            }

            Text(
                text = "Accent",
                style = MaterialTheme.typography.titleMedium,
                color = theme.textPrimary,
                modifier = Modifier.testTag(settingsEntryTag("Accent")),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ACCENT_PRESETS.forEach { (label, color) ->
                    val selected = color == theme.accent
                    Box(
                        modifier = Modifier
                            .testTag("accent-swatch-$label")
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(color)
                            .then(
                                if (selected) {
                                    Modifier.border(2.dp, theme.textPrimary, CircleShape)
                                } else {
                                    Modifier
                                },
                            )
                            .clickable { onThemeChange(theme.copy(accent = color)) },
                    )
                }
            }

            Text(
                text = "Reader",
                style = MaterialTheme.typography.titleMedium,
                color = theme.textPrimary,
            )
            // Local state for live drag feedback; persisted only in onValueChangeFinished so
            // dragging the slider doesn't write to disk on every pixel of movement.
            var pending by remember(autoScrollSpeed) { mutableStateOf(autoScrollSpeed) }
            Text(
                text = "Auto-scroll speed: ${pending.roundToInt()} dp/s",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.textPrimary,
                modifier = Modifier.testTag(settingsEntryTag("Auto-scroll speed")),
            )
            Slider(
                value = pending,
                onValueChange = { pending = it },
                onValueChangeFinished = { onAutoScrollSpeedChange(pending) },
                valueRange = 30f..600f,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "General",
                style = MaterialTheme.typography.titleMedium,
                color = theme.textPrimary,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.testTag(settingsEntryTag("Library view")),
            ) {
                Text(
                    text = "Library view",
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                SegmentedControl(
                    options = listOf("Grid", "List"),
                    selectedIndex = if (libraryView == LIBRARY_VIEW_LIST) 1 else 0,
                    onSelect = { index -> onLibraryViewChange(if (index == 0) LIBRARY_VIEW_GRID else LIBRARY_VIEW_LIST) },
                )
            }
        }
    }
}
