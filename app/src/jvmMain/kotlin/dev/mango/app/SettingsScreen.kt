package dev.mango.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Pure, data-driven content — the screenshot harness renders this directly. No stateful loader
 * needed: the current theme and the change callback are hoisted all the way up to Main.kt (M4.4a)
 * so the switch applies live without a restart. Same pattern for the M6(b) auto-scroll speed.
 */
@Composable
fun SettingsScreenContent(
    themeNames: List<String>,
    currentTheme: String,
    onSelectTheme: (String) -> Unit,
    autoScrollSpeed: Float = 120f,
    onAutoScrollSpeedChange: (Float) -> Unit = {},
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Theme",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                themeNames.forEach { name ->
                    FilterChip(
                        selected = name == currentTheme,
                        onClick = { onSelectTheme(name) },
                        label = { Text(name) },
                    )
                }
            }
            Text(
                text = "Reader",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            // Local state for live drag feedback; persisted only in onValueChangeFinished so
            // dragging the slider doesn't write to disk on every pixel of movement.
            var pending by remember(autoScrollSpeed) { mutableStateOf(autoScrollSpeed) }
            Text(
                text = "Auto-scroll speed: ${pending.roundToInt()} dp/s",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Slider(
                value = pending,
                onValueChange = { pending = it },
                onValueChangeFinished = { onAutoScrollSpeedChange(pending) },
                valueRange = 30f..600f,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
