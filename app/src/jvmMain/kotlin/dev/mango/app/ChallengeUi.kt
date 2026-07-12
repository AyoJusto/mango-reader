package dev.mango.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The inline "solving this source's challenge" hint shown while a challenge solve is running. */
@Composable
internal fun SolveProgressHint() {
    val theme = LocalMangoTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MangoSpace.base),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            color = theme.accent,
            strokeWidth = 2.dp,
            trackColor = Color.Transparent,
        )
        Text(
            text = "Solving site challenge… ~15 s",
            style = MangoType.caption,
            color = theme.textSecondary,
        )
    }
}

/**
 * Warning-toned card plus an optional Solve button for a Cloudflare challenge: the user did
 * nothing wrong, so this reads as warning, never danger (see board 11 in the design handoff).
 *
 * [solveEnabled] and [solving] are distinct: a solve running anywhere disables the button
 * ([solveEnabled]), but only the screen whose own solve is running shows the progress hint
 * ([solving]). [onRetry] is null when the caller has no separate retry action; passing it renders
 * a secondary Retry button alongside Solve.
 */
@Composable
internal fun ChallengeErrorContent(
    error: String,
    challengeUrl: String?,
    solving: Boolean,
    solveEnabled: Boolean,
    onSolveChallenge: () -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    val theme = LocalMangoTheme.current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(MangoRadius.row))
            .background(theme.warning.copy(alpha = 0.10f))
            .padding(MangoSpace.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MangoSpace.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm)) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(theme.warning),
            )
            Text(text = error, style = MangoType.bodyStrong, color = theme.textPrimary)
        }
        if (challengeUrl != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm)) {
                KitButton(
                    label = "Solve challenge",
                    onClick = onSolveChallenge,
                    style = KitButtonStyle.PRIMARY,
                    enabled = solveEnabled,
                )
                if (onRetry != null) {
                    KitButton(
                        label = "Retry",
                        onClick = onRetry,
                        style = KitButtonStyle.SECONDARY,
                        enabled = solveEnabled,
                    )
                }
            }
            if (solving) {
                SolveProgressHint()
            }
        }
    }
}
