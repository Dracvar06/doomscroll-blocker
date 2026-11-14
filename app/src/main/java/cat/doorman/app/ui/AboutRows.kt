package cat.doorman.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import cat.doorman.app.R

/**
 * Support, in a line; the reasons are one tap away rather than a paragraph in
 * the middle of the settings.
 */
@Composable
fun SupportRow(onCoffee: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    NavRow(
        icon = Icons.Outlined.FavoriteBorder,
        title = stringResource(R.string.support_title),
        supporting = stringResource(R.string.support_row_line),
        onClick = { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.support_title)) },
            text = { Text(stringResource(R.string.support_body)) },
            confirmButton = {
                TextButton(onClick = { open = false; onCoffee() }) {
                    Text(stringResource(R.string.action_coffee))
                }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(R.string.action_close_help))
                }
            },
        )
    }
}
