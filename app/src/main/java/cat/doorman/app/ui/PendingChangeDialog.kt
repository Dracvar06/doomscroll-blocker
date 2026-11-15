package cat.doorman.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    totalSeconds: Int,
    title: String,
    /** What is changing, one line each: "From Blocked to 20 minutes a day". */
    details: List<String>,
    onCancel: () -> Unit,
) {
    if (seconds <= 0) return
    AlertDialog(
        onDismissRequest = { },
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = {
            // Scrolls: a plan can list a dozen things that weaken, and in a large
            // font the dialog would otherwise run off the screen.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The bar empties as the wait runs out, so how long is left
                // can be seen from across the room rather than read.
                val left = stringResource(R.string.pending_progress_desc, seconds)
                LinearProgressIndicator(
                    progress = { seconds.toFloat() / totalSeconds.coerceAtLeast(1) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = left },
                )
                details.forEach { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
                Text(
                    text = stringResource(R.string.pending_change_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.pending_change_keep))
            }
        },
    )
}
