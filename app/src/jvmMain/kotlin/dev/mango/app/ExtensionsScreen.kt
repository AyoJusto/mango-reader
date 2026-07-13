package dev.mango.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
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
    onDismissActionError: () -> Unit = {},
    onInstall: (AvailableSource) -> Unit,
    onRemove: (AvailableSource) -> Unit,
) {
    val theme = LocalMangoTheme.current
    Surface(modifier = Modifier.fillMaxSize(), color = theme.bg0) {
        ContentColumn(max = MangoSpace.contentMaxWidth) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    isLoading -> ExtensionsLoadingSkeleton(modifier = Modifier.fillMaxSize())
                    // The initial load has no list to show yet, so its failure takes over the whole
                    // screen; an install/remove failure must not reuse this state or it would blank
                    // an already-loaded list.
                    error != null -> Text(
                        text = error,
                        style = MangoType.body,
                        color = theme.danger,
                    )

                    available.isEmpty() -> EmptyState(
                        title = "No extensions available",
                        guidance = "Nothing in the registry right now — press Shift-Shift to search everywhere.",
                    )

                    else -> Column(modifier = Modifier.fillMaxSize()) {
                        if (actionError != null) {
                            ErrorBanner(
                                headline = actionError,
                                detail = "Your installed sources are untouched below.",
                                onDismiss = onDismissActionError,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        }
                        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                            items(available, key = { it.sourceId }) { source ->
                                ExtensionRow(
                                    source = source,
                                    installedVersion = installed[source.sourceId],
                                    busy = source.sourceId in busy,
                                    onInstall = { onInstall(source) },
                                    onRemove = { onRemove(source) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One extension row: icon tile, name + version/language meta, and the install/update/remove action. */
@Composable
private fun ExtensionRow(
    source: AvailableSource,
    installedVersion: String?,
    busy: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    val theme = LocalMangoTheme.current
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = MangoSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExtensionIconTile(name = source.name)
            Spacer(modifier = Modifier.width(MangoSpace.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.name,
                    style = MangoType.bodyStrong,
                    color = theme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Version is always present; language is skipped silently when absent, and
                // there is no "series in library" count on this data to show here.
                val metaSegments = listOfNotNull("v${source.version}", source.language?.uppercase())
                if (metaSegments.isNotEmpty()) {
                    Text(
                        text = metaSegments.joinToString(" · "),
                        style = MangoType.caption,
                        color = theme.textSecondary
                    )
                }
            }
            Spacer(modifier = Modifier.width(MangoSpace.sm))
            when {
                busy -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                installedVersion == null -> KitButton(
                    label = "Install",
                    onClick = onInstall,
                    style = KitButtonStyle.PRIMARY
                )

                installedVersion == source.version -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm),
                ) {
                    Text(text = "Installed", style = MangoType.bodyStrong, color = theme.accent)
                    KitButton(label = "Remove", onClick = onRemove, style = KitButtonStyle.DANGER)
                }

                else -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm),
                ) {
                    KitButton(
                        label = "Update to ${source.version}",
                        onClick = onInstall,
                        style = KitButtonStyle.PRIMARY
                    )
                    KitButton(label = "Remove", onClick = onRemove, style = KitButtonStyle.DANGER)
                }
            }
        }
        HorizontalDivider(color = theme.divider)
    }
}

/** 36dp icon tile: an initial-letter placeholder, since [AvailableSource] carries no icon data. */
@Composable
private fun ExtensionIconTile(name: String) {
    val theme = LocalMangoTheme.current
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(MangoRadius.control))
            .background(theme.bg2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1).uppercase(),
            style = MangoType.bodyStrong,
            color = theme.textSecondary,
        )
    }
}

/** Loading placeholder mirroring the row layout: icon tile + two text lines, shimmering. */
@Composable
private fun ExtensionsLoadingSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        repeat(5) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = MangoSpace.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBlock(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(MangoRadius.control)))
                Spacer(modifier = Modifier.width(MangoSpace.sm))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MangoSpace.base)) {
                    SkeletonBlock(
                        modifier = Modifier.width(160.dp).height(14.dp).clip(RoundedCornerShape(MangoRadius.keycap)),
                    )
                    SkeletonBlock(
                        modifier = Modifier.width(90.dp).height(11.dp).clip(RoundedCornerShape(MangoRadius.keycap)),
                    )
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
        onDismissActionError = { actionError = null },
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
