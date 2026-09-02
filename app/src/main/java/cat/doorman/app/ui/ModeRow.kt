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
import cat.doorman.app.limits.BlockMode
import cat.doorman.app.limits.Period

/**
 * The name of a mode, as the user reads it.
 *
 * Built from plurals rather than by pasting a number in front of a word,
 * because "1 minut" and "5 minuts" differ in Catalan and Spanish and this text
 * is the whole interface to the feature.
 */
@Composable
fun modeLabel(mode: BlockMode): String = when (mode) {
    is BlockMode.Off -> stringResource(R.string.mode_off)
    is BlockMode.Blocked -> stringResource(R.string.mode_blocked)
    is BlockMode.Allowance -> {
        val minutes = pluralStringResource(R.plurals.minutes, mode.minutes, mode.minutes)
        when (mode.period) {
            Period.HOUR -> stringResource(R.string.mode_allowance_hour, minutes)
            Period.DAY -> stringResource(R.string.mode_allowance_day, minutes)
        }
    }
}

/**
 * One thing that can be held, with its current mode and a way to change it.
 *
 * Collapsed by default. Eight choices per screen laid out permanently would
 * turn a list someone can read into a wall they scroll past, and the common
 * case is that nothing needs changing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModeRow(
    label: String,
    mode: BlockMode,
    remaining: String?,
    onModeChosen: (BlockMode) -> Unit,
    icon: ImageBitmap? = null,
    hasIcon: Boolean = false,
    help: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The space is held from the start, before the icon has
                // finished loading. Drawing it only once it arrives would make
                // every row in a long list slide sideways under the user's
                // thumb as the icons trickle in.
                if (hasIcon) {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
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
                text = remaining ?: modeLabel(mode),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (expanded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(BlockMode.Blocked) + BlockMode.OFFERED + listOf(BlockMode.Off))
                    .forEach { choice ->
                        FilterChip(
                            selected = choice == mode,
                            onClick = {
                                onModeChosen(choice)
                                expanded = false
                            },
                            label = { Text(modeLabel(choice)) },
                        )
                    }
            }
        }
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
