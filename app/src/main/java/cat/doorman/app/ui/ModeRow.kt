package cat.doorman.app.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cat.doorman.app.R
import cat.doorman.app.gray.GraySetting
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
 *
 * Every screen is this one row, including the ones that cannot have a time
 * limit; they open an editor with only Allowed and Blocked in it. The state is
 * a line of words under the name, with a dot beside it that differs in shape
 * and not only in colour. Words in a column of their own beside the name
 * fought it for the width in Catalan at a large font, and the name lost.
 */
@Composable
fun LimitRow(
    label: String,
    limits: Limits,
    remaining: String?,
    onSaved: (Limits, GraySetting?) -> Unit,
    icon: ImageBitmap? = null,
    hasIcon: Boolean = false,
    help: String? = null,
    suggestions: List<Window> = emptyList(),
    reelsAllowed: Boolean = false,
    supportsAllowance: Boolean = true,
    gray: GraySetting? = null,
    supportsGrey: Boolean = false,
    grayCanWrite: Boolean = false,
    changeDelaySeconds: Int = 0,
) {
    var editing by remember { mutableStateOf(false) }
    val res = LocalResources.current
    // Grey is said in words after the limit, not left to a colour: it is a
    // second thing holding the screen and the row has to name both.
    val summary = listOfNotNull(
        limitsSummary(limits).let { text ->
            if (remaining == null || limits.isOff) text else "$text · $remaining"
        },
        gray?.let { graySummaryText(res, it) },
    ).joinToString(" · ")
    Row(
        // One node for a screen reader: the name, then what it is doing.
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button) { editing = true }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The space is held from the start, before the icon has finished
        // loading. Drawing it only once it arrives would make every row in
        // a long list slide sideways under the user's thumb.
        if (hasIcon) {
            AppAvatar(icon = icon, label = label, size = 40.dp, loading = icon == null)
            Spacer(Modifier.width(12.dp))
        }
        StatusDot(limits)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        // The arrow is what says "this opens something".
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
            reelsAllowed = reelsAllowed,
            supportsAllowance = supportsAllowance,
            gray = gray,
            supportsGrey = supportsGrey,
            grayCanWrite = grayCanWrite,
            changeDelaySeconds = changeDelaySeconds,
            onDismiss = { editing = false },
            onSave = { chosen, chosenGray ->
                onSaved(chosen, chosenGray)
                editing = false
            },
        )
    }
}

/**
 * Whether something is held, in the shape of a dot: full when blocked, half
 * when something limits it, empty when it is allowed. Decorative, because the
 * words beside it say the same.
 */
@Composable
private fun StatusDot(limits: Limits) {
    val colour = if (limits.isOff) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.primary
    }
    Canvas(Modifier.size(12.dp)) {
        val stroke = 1.5.dp.toPx()
        drawCircle(colour, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
        when {
            limits.blocked -> drawCircle(colour)
            !limits.isOff -> drawArc(
                colour,
                startAngle = 90f,
                sweepAngle = 180f,
                useCenter = true,
            )
        }
    }
}

/**
 * A plain switch, for a setting that is simply on or off and has no screen of
 * limits behind it.
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
            contentDescription = stringResource(R.string.action_help_for, label),
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
