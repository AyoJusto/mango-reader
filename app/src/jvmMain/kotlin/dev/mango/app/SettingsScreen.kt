package dev.mango.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Pure, data-driven content — the screenshot harness renders this directly. No stateful loader
 * needed: the current theme and the change callback are hoisted all the way up to Main.kt (M4.4a)
 * so the switch applies live without a restart.
 */
@Composable
fun SettingsScreenContent(
    themeNames: List<String>,
    currentTheme: String,
    onSelectTheme: (String) -> Unit,
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
        }
    }
}
