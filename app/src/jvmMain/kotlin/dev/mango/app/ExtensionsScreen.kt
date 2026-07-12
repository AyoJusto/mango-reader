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
import androidx.compose.material3.TextButton
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
    actionError: String? = null,
    onInstall: (AvailableSource) -> Unit,
    onRemove: (AvailableSource) -> Unit,
) {
    val theme = LocalMangoTheme.current
    Surface(modifier = Modifier.fillMaxSize(), color = theme.bg0) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                isLoading -> CircularProgressIndicator()
                // The initial load has no list to show yet, so its failure takes over the whole
                // screen; an install/remove failure must not reuse this state or it would blank
                // an already-loaded list.
                error != null -> Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.danger,
                )
                available.isEmpty() -> Text(
                    text = "No extensions available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.textSecondary,
                )
                else -> Column(modifier = Modifier.fillMaxSize()) {
                    if (actionError != null) {
                        Text(
                            text = actionError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = theme.danger,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
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
                                            color = theme.textPrimary,
                                        )
                                        Text(
                                            text = "${source.sourceId} · ${source.language ?: "?"} · v${source.version}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = theme.textSecondary,
                                        )
                                    }
                                    when {
                                        source.sourceId in busy ->
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                        installedVersion == null -> Button(onClick = { onInstall(source) }) {
                                            Text("Install")
                                        }
                                        installedVersion == source.version -> Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "Installed",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = theme.accent,
                                            )
                                            TextButton(onClick = { onRemove(source) }) {
                                                Text("Remove")
                                            }
                                        }
                                        else -> Row(verticalAlignment = Alignment.CenterVertically) {
                                            Button(onClick = { onInstall(source) }) {
                                                Text("Update to ${source.version}")
                                            }
                                            TextButton(onClick = { onRemove(source) }) {
                                                Text("Remove")
                                            }
                                        }
                                    }
                                }
                                HorizontalDivider(color = theme.divider)
                            }
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
    var actionError by remember { mutableStateOf<String?>(null) }

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
        actionError = actionError,
        onInstall = { source ->
            // busy is set synchronously, before the coroutine body runs: a second click on
            // the same row bails here instead of launching a duplicate install
            if (source.sourceId !in busy) {
                busy = busy + source.sourceId
                actionError = null
                scope.launch {
                    try {
                        repo.install(source)
                        refreshInstalled()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        actionError = e.message ?: "Install failed"
                    } finally {
                        busy = busy - source.sourceId
                    }
                }
            }
        },
        onRemove = { source ->
            // same busy-guard shape as onInstall: set synchronously so a second click on the
            // same row bails here instead of launching a duplicate uninstall
            if (source.sourceId !in busy) {
                busy = busy + source.sourceId
                actionError = null
                scope.launch {
                    try {
                        catalog.uninstall(source.sourceId)
                        refreshInstalled()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        actionError = e.message ?: "Remove failed"
                    } finally {
                        busy = busy - source.sourceId
                    }
                }
            }
        },
    )
}
