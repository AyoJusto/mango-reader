package dev.mango.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The "Opening browser… (first run downloads it, ~100MB)" progress hint shown while a challenge solve is running. */
@Composable
internal fun SolveProgressHint() {
    Text(
        text = "Opening browser… (first run downloads it, ~100MB)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Error text plus an optional Solve button for a Cloudflare challenge.
 *
 * [solveEnabled] and [solving] are distinct: a solve running anywhere disables the button
 * ([solveEnabled]), but only the screen whose own solve is running shows the progress hint
 * ([solving]).
 */
@Composable
internal fun ChallengeErrorContent(
    error: String,
    challengeUrl: String?,
    solving: Boolean,
    solveEnabled: Boolean,
    onSolveChallenge: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        if (challengeUrl != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onSolveChallenge, enabled = solveEnabled) { Text("Solve challenge") }
            if (solving) {
                SolveProgressHint()
            }
        }
    }
}
