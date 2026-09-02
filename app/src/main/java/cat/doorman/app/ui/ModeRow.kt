package cat.doorman.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cat.doorman.app.R
import cat.doorman.app.limits.Limits

/**
 * One thing that can be held: what is holding it, and a way in to change it.
 *
 * The row used to expand into a strip of preset chips. That worked while there
 * were eight presets and stopped working the moment limits could be combined --
 * a budget and two blocked stretches on different days is not a preset, and
 * laying every possibility out as chips would be a wall. Tapping opens an
 * editor instead, and the row itself says only what is true right now.
 */
@Composable
fun LimitRow(
    label: String,
    limits: Limits,
    remaining: String?,
    onLimitsChosen: (Limits) -> Unit,
    icon: ImageBitmap? = null,
    hasIcon: Boolean = false,
    help: String? = null,
) {
    var editing by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { editing = true }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The space is held from the start, before the icon has finished
            // loading. Drawing it only once it arrives would make every row in
            // a long list slide sideways under the user's thumb.
            if (hasIcon) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    if (icon != null) {
                        Image(
                            bitmap = icon,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            HelpButton(label, help)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = remaining ?: limitsSummary(limits),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (editing) {
        LimitsEditor(
            title = label,
            limits = limits,
            help = help,
            onDismiss = { editing = false },
            onSave = {
                onLimitsChosen(it)
                editing = false
            },
        )
    }
}

/**
 * A plain switch, for the things an allowance makes no sense of.
 *
 * "Keep swiping from a video someone sent" is a behaviour, not a place you
 * spend minutes in, and neither is "the stories row on the home feed". Offering
 * to allow either of them for five minutes a day would be offering nonsense.
 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    help: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The label and its info button take what is left after the switch,
        // rather than the switch taking what is left after them: a long label
        // was pushing the button underneath the toggle.
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f, fill = false),
            )
            HelpButton(label, help)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * The info button beside a block, and what it says.
 *
 * "Reels" and "keep swiping from a reel someone sent" are different things and
 * the labels cannot say so in two words. Someone who cannot tell which switch
 * is which will either block more than they meant and resent the app, or block
 * less and think it is broken -- and both end with Doorman switched off.
 *
 * Nothing is shown when a block has no explanation written yet, rather than an
 * button that opens an empty box.
 */
@Composable
private fun HelpButton(label: String, help: String?) {
    if (help.isNullOrBlank()) return
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.action_help)
    IconButton(
        onClick = { open = true },
        // The glyph is a letter as far as a screen reader is concerned, so the
        // button needs a name of its own. An app built on the accessibility
        // framework should not ship a control that announces itself as "i".
        modifier = Modifier
            .size(32.dp)
            .semantics { contentDescription = description },
    ) {
        Text(
            text = "\u24d8",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = { Text(help, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(R.string.action_close_help))
                }
            },
        )
    }
}
