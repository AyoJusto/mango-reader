package dev.mango.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mango.core.domain.AvailableSource
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.ExtensionRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Pure, data-driven content — the screenshot harness renders this directly. */
@Composable
fun ExtensionsScreenContent(
    available: List<AvailableSource>,
    installed: Map<String, String>,
    busy: Set<String>,
    isLoading: Boolean,
    error: String?,
    onInstall: (AvailableSource) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                isLoading -> CircularProgressIndicator()
                error != null -> Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                available.isEmpty() -> Text(
                    text = "No extensions available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    items(available, key = { it.sourceId }) { source ->
                        val installedVersion = installed[source.sourceId]
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = source.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "${source.sourceId} · ${source.language ?: "?"} · v${source.version}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                when {
                                    source.sourceId in busy ->
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                    installedVersion == null -> Button(onClick = { onInstall(source) }) {
                                        Text("Install")
                                    }
                                    installedVersion == source.version -> Text(
                                        text = "Installed",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    else -> Button(onClick = { onInstall(source) }) {
                                        Text("Update to ${source.version}")
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Stateful loader: [ExtensionRepo.available] plus [CatalogRepository.installedSources] on
 * first composition, refreshing the installed map after each install/update completes.
 */
@Composable
fun ExtensionsScreen(repo: ExtensionRepo, catalog: CatalogRepository) {
    val scope = rememberCoroutineScope()
    var available by remember { mutableStateOf<List<AvailableSource>>(emptyList()) }
    var installed by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var busy by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refreshInstalled() {
        installed = catalog.installedSources().associate { it.sourceId to it.version }
    }

    LaunchedEffect(Unit) {
        isLoading = true
        error = null
        try {
            available = repo.available()
            refreshInstalled()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Failed to load extensions"
        } finally {
            isLoading = false
        }
    }

    ExtensionsScreenContent(
        available = available,
        installed = installed,
        busy = busy,
        isLoading = isLoading,
        error = error,
        onInstall = { source ->
            // busy is set synchronously, before the coroutine body runs: a second click on
            // the same row bails here instead of launching a duplicate install
            if (source.sourceId !in busy) {
                busy = busy + source.sourceId
                scope.launch {
                    try {
                        repo.install(source)
                        refreshInstalled()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "Install failed"
                    } finally {
                        busy = busy - source.sourceId
                    }
                }
            }
        },
    )
}
