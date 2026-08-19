package cat.doorman.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cat.doorman.app.R

/**
 * The screen that decides whether anyone but the author can keep this working.
 *
 * Screen fingerprints are derived from whatever version of YouTube or Instagram
 * happened to be installed when they were written, and those apps redesign
 * themselves on their own schedule. When a rule breaks, Doorman fails open --
 * safe, but silent. Without a way for an ordinary user to say "here is what my
 * screen looks like now", every break needs someone with adb and a cable.
 */
@Composable
fun DiagnosticsSection(
    lastSeen: String?,
    countdown: Int,
    reportReady: Boolean,
    captureFailed: Boolean,
    onCapture: () -> Unit,
    onShare: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.section_diagnostics),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.diagnostics_explainer),
            style = MaterialTheme.typography.bodyMedium,
        )
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (lastSeen.isNullOrBlank()) {
                        stringResource(R.string.diagnostics_last_seen_none)
                    } else {
                        stringResource(R.string.diagnostics_last_seen, lastSeen)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                when {
                    countdown > 0 -> Text(
                        text = stringResource(R.string.diagnostics_capturing, countdown),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    reportReady -> {
                        Text(
                            text = stringResource(R.string.diagnostics_captured),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = onShare) {
                            Text(stringResource(R.string.action_share_report))
                        }
                        TextButton(onClick = onCapture) {
                            Text(stringResource(R.string.action_capture_report))
                        }
                    }
                    else -> {
                        if (captureFailed) {
                            Text(
                                text = stringResource(R.string.diagnostics_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Text(
                            text = stringResource(R.string.diagnostics_capture_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = onCapture) {
                            Text(stringResource(R.string.action_capture_report))
                        }
                    }
                }
            }
        }
    }
}
