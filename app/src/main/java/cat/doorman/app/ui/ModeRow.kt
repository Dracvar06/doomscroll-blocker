package cat.doorman.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cat.doorman.app.R
import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.Window

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
    suggestions: List<Window> = emptyList(),
) {
    var editing by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { editing = true },
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
                AppAvatar(icon = icon, label = label, size = 40.dp, loading = icon == null)
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f, fill = false),
            )
            HelpButton(label, help)
        }
        Spacer(Modifier.width(8.dp))
        // Same rule as the cards above: accent for a row that is holding
        // something, quiet for one that is not. A list of twenty rows should
        // answer "what is on?" at a glance rather than by being read.
        Text(
            text = remaining ?: limitsSummary(limits),
            style = MaterialTheme.typography.labelLarge,
            color = if (limits.isOff) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        // The arrow is what says "this opens something". Without it a row of
        // the list and a row with a switch looked like the same kind of thing,
        // and the only way to learn that one opens an editor was to tap it.
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (editing) {
        LimitsEditor(
            title = label,
            limits = limits,
            help = help,
            suggestions = suggestions,
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
    // The whole row is the switch, not just the thumb: a 56dp row is a target
    // a thumb finds without looking, and a screen reader reads it as one
    // control with its label instead of a label and an unnamed toggle.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
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
        Switch(checked = checked, onCheckedChange = null)
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
    // A full 48dp target around a small icon. The old 32dp button with a
    // text "i" in it was under Android's minimum and easy to miss for the
    // row underneath it.
    IconButton(onClick = { open = true }) {
        Icon(
            Icons.Outlined.Info,
            // Named, because an app built on the accessibility framework
            // should not ship a control a screen reader announces as nothing.
            contentDescription = stringResource(R.string.action_help),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
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
