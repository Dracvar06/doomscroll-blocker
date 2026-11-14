package cat.doorman.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cat.doorman.app.R

/** The waits worth a button; anything between them is what Custom is for. */
private val DELAY_CHOICES = listOf(0, 10, 30, 60, 300)

/**
 * How long weakening a block waits, as the handful of values people pick and
 * a sentence saying what that means for them.
 *
 * The dial it replaced was 210 dp of the first screen of Settings for a choice
 * made once. It is still here, behind Custom, for the wait that is none of
 * these.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DelayRow(seconds: Int, onChange: (Int) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Title, value and example read as one thing; the chips below are
        // controls of their own.
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) { },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                stringResource(R.string.delay_row_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                delayLabel(seconds),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                if (seconds == 0) {
                    stringResource(R.string.delay_row_example_instant)
                } else {
                    stringResource(R.string.delay_row_example, delayLabel(seconds))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DELAY_CHOICES.forEach { choice ->
                FilterChip(
                    selected = seconds == choice,
                    onClick = { onChange(choice) },
                    label = { Text(delayLabel(choice)) },
                )
            }
            FilterChip(
                selected = seconds !in DELAY_CHOICES,
                onClick = { custom = true },
                label = { Text(stringResource(R.string.delay_custom)) },
            )
        }
    }
    if (custom) {
        // The dial moves a copy, and only Got it hands it on. Applied live,
        // Cancel would have to put the old wait back, and putting a shorter
        // one back is a weakening that waits out the delay.
        var draft by remember { mutableStateOf(seconds) }
        AlertDialog(
            onDismissRequest = { custom = false },
            title = { Text(stringResource(R.string.delay_row_title)) },
            text = {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    DelayDial(seconds = draft, onChange = { draft = it })
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (draft != seconds) onChange(draft)
                        custom = false
                    },
                ) {
                    Text(stringResource(R.string.action_close_help))
                }
            },
            dismissButton = {
                TextButton(onClick = { custom = false }) {
                    Text(stringResource(R.string.action_cancel_change))
                }
            },
        )
    }
}
