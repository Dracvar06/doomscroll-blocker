package cat.doorman.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cat.doorman.app.R

/**
 * Typing a duration in, for when the dial is not what you want.
 *
 * A dial is pleasant and approximate. Some people know exactly what they mean
 * -- three minutes, or two and a half hours -- and making them hunt for it
 * around a circle is making them work for something they could have said. So
 * the number in the middle of every dial is a button, and this is what it
 * opens.
 *
 * Two fields rather than one, because "150" is not how anybody holds two and a
 * half hours in their head.
 */
@Composable
fun DurationEntryDialog(
    majorLabel: String,
    minorLabel: String,
    major: Int,
    minor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    var majorText by remember { mutableStateOf(if (major == 0) "" else major.toString()) }
    var minorText by remember { mutableStateOf(if (minor == 0) "" else minor.toString()) }
    val focus = remember { FocusRequester() }
    // The keyboard comes up with the dialog. Anyone who has gone looking for
    // this has already decided to type, and a dialog that then asks for one
    // more tap before it will listen is a dialog that wasted the trip.
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dial_type_title)) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    value = majorText,
                    label = majorLabel,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    onChange = { majorText = it },
                )
                NumberField(
                    value = minorText,
                    label = minorLabel,
                    modifier = Modifier.weight(1f),
                    onChange = { minorText = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(majorText.toIntOrNull() ?: 0, minorText.toIntOrNull() ?: 0)
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel_change))
            }
        },
    )
}

/**
 * Digits only, and never more than three of them.
 *
 * Filtering as it is typed rather than complaining afterwards: there is no
 * meaning to rescue from a letter in a minutes field, so the honest thing is
 * for it never to appear.
 */
@Composable
private fun NumberField(
    value: String,
    label: String,
    modifier: Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { typed -> onChange(typed.filter { it.isDigit() }.take(3)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
