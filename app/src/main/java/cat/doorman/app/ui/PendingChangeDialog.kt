package cat.doorman.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cat.doorman.app.R

/**
 * The wait a loosening change has to sit through, as something that stops you
 * rather than something you scroll past.
 *
 * It used to be a card in the middle of the settings list, which meant the
 * countdown could be scrolled off screen and the change would land with no
 * warning still visible. The point of the wait is to be in the way of an
 * impulse, so it is in the way.
 *
 * Deliberately not dismissible by tapping outside or pressing back. Those are
 * the two gestures a person makes without deciding anything, and either
 * silently abandoning the change or silently confirming it would be a
 * surprise. The only ways out are the button and leaving the screen, which
 * cancels.
 */
@Composable
fun PendingChangeDialog(
    seconds: Int,
    isDelayChange: Boolean,
    targetLabel: String?,
    modeLabel: String?,
    onCancel: () -> Unit,
) {
    if (seconds <= 0) return
    AlertDialog(
        onDismissRequest = { },
        title = {
            Text(
                text = when {
                    isDelayChange -> stringResource(R.string.pending_change_delay, seconds)
                    targetLabel != null && modeLabel != null -> stringResource(
                        R.string.pending_change_mode, targetLabel, modeLabel, seconds,
                    )
                    targetLabel != null ->
                        stringResource(R.string.pending_change_screen, targetLabel, seconds)
                    else -> stringResource(R.string.pending_change_preset, seconds)
                },
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.pending_change_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel_change))
            }
        },
    )
}
