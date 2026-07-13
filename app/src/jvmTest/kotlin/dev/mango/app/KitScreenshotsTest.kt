package dev.mango.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * One board rendering every [Kit.kt][dev.mango.app] primitive in every static state (hover is
 * excluded — it needs a live pointer) on bg0. This is the kit's visual contract: the screen restyles
 * restyle screens against what this board shows.
 */
class KitScreenshotsTest {
    @Test
    fun kit() {
        val file = Screenshots.render("kit", height = 2000) {
            ProvideMangoTheme(MangoDark) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MangoDark.bg0)
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        KitButton(label = "Primary", onClick = {}, style = KitButtonStyle.PRIMARY)
                        KitButton(label = "Secondary", onClick = {}, style = KitButtonStyle.SECONDARY)
                        KitButton(label = "Ghost", onClick = {}, style = KitButtonStyle.GHOST)
                        KitButton(label = "Danger", onClick = {}, style = KitButtonStyle.DANGER)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Pill(
                            text = "12",
                            container = MangoDark.accent.copy(alpha = 0.92f),
                            content = MangoDark.accentOn
                        )
                        Pill(
                            text = "In library",
                            container = MangoDark.success.copy(alpha = 0.14f),
                            content = MangoDark.success
                        )
                        Keycap(text = "esc")
                        Keycap(text = "ctrl s")
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProgressTrack(progress = 0.4f, modifier = Modifier.width(240.dp))
                        ProgressTrack(progress = 1.0f, modifier = Modifier.width(240.dp))
                    }

                    SegmentedControl(
                        options = listOf("Grid", "List"),
                        selectedIndex = 0,
                        onSelect = {},
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CoverCard(
                            title = "Solo Leveling",
                            coverUrl = null,
                            metaLine = "Ch. 142 · 72%",
                            unreadCount = 3,
                            progress = 0.72f,
                            finished = false,
                            onClick = {},
                            modifier = Modifier.width(140.dp),
                        )
                        CoverCard(
                            title = "Tower of God",
                            coverUrl = null,
                            metaLine = "Ch. 588",
                            unreadCount = null,
                            progress = null,
                            finished = true,
                            onClick = {},
                            modifier = Modifier.width(140.dp),
                        )
                    }

                    SkeletonBlock(modifier = Modifier.width(140.dp).height(210.dp))

                    EmptyState(
                        title = "Nothing here yet",
                        guidance = "Press Shift-Shift to browse a source and add manhwa.",
                        ctaLabel = "Browse sources",
                        onCta = {},
                    )

                    ErrorBanner(
                        headline = "Failed to load extensions.",
                        detail = "Your installed sources are untouched below.",
                        onRetry = {},
                        onDismiss = {},
                    )
                }
            }
        }
        assertTrue(Files.size(file) > 0, "expected a non-empty PNG at $file")
    }
}
