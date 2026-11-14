package cat.doorman.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cat.doorman.app.R
import cat.doorman.app.data.SetupStage
import cat.doorman.app.limits.Limits
import cat.doorman.app.rules.RuleLoader
import cat.doorman.app.rules.RuleSet

/**
 * The starting levels, as the screens each one holds.
 *
 * Four rather than three. The old set went "everything", "the usual",
 * "nothing", and the gap between the last two was a cliff: anyone who found the
 * balanced setting too much had nowhere left to go but off.
 */
fun presetsFor(rules: RuleSet): List<Pair<Int, Set<String>>> {
    val allScreens = rules.apps.values.flatMap { it.screens }
    return listOf(
        R.string.preset_strict to allScreens.map { it.id }.toSet(),
        R.string.preset_balanced to allScreens.filter { it.defaultEnabled }.map { it.id }.toSet(),
        R.string.preset_permissive to RuleLoader.endlessScreenIds(rules),
        R.string.preset_nothing to emptySet(),
    )
}

/**
 * The screens held right now, for comparing with a preset. A preset is shown as
 * chosen only when the set matches exactly, which is also how somebody can tell
 * that changing a single switch has taken them off all of them.
 */
private fun heldScreens(rules: RuleSet, screenLimits: Map<String, Limits>): Set<String> =
    rules.apps.values.flatMap { it.screens }
        .filter { !(screenLimits[it.id] ?: Limits.OFF).isOff }
        .map { it.id }.toSet()

/**
 * Three things to do before Doorman is any use, at the top of Blocks until they
 * are done or waved away.
 *
 * A checklist rather than a tour: the old walkthrough explained, and then left
 * people on a red card to work out the order for themselves. Each item does its
 * thing from here. The last is optional and does not hold the card open.
 */
@Composable
fun SetupCard(
    rules: RuleSet,
    screenLimits: Map<String, Limits>,
    serviceOn: Boolean,
    stage: SetupStage,
    notificationsOn: Boolean,
    onEnable: () -> Unit,
    onPickLevel: (String, Set<String>) -> Unit,
    onNotify: () -> Unit,
    onDismiss: () -> Unit,
) {
    SurfaceCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.setup_title),
                style = MaterialTheme.typography.titleLarge,
            )

            SetupStep(
                number = 1,
                done = serviceOn,
                title = stringResource(R.string.action_enable_doorman),
                hint = stringResource(R.string.setup_step_enable_hint),
                onClick = if (serviceOn) null else onEnable,
            )

            SetupStep(
                number = 2,
                done = stage != SetupStage.OPEN,
                title = stringResource(R.string.setup_step_level),
                hint = null,
                onClick = null,
            )
            // Chosen only once chosen: before that the limits are the shipped
            // defaults, which happen to equal Balanced, and a card with an
            // answer already ticked is a question nobody read.
            val held = heldScreens(rules, screenLimits)
            val examples = listOf(
                R.string.setup_level_strict_example,
                R.string.setup_level_balanced_example,
                R.string.setup_level_permissive_example,
            )
            presetsFor(rules).take(examples.size).forEachIndexed { index, (label, ids) ->
                val name = stringResource(label)
                LevelOption(
                    name = name,
                    example = stringResource(examples[index]),
                    selected = stage != SetupStage.OPEN && held == ids,
                    onClick = { onPickLevel(name, ids) },
                )
            }

            SetupStep(
                number = 3,
                done = notificationsOn,
                title = stringResource(R.string.setup_step_notify),
                hint = null,
                onClick = if (notificationsOn) null else onNotify,
            )

            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.setup_dismiss))
            }
        }
    }
}

/** A numbered row that becomes a tick once it is done. */
@Composable
private fun SetupStep(
    number: Int,
    done: Boolean,
    title: String,
    hint: String?,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A tick against a numbered ring, not a colour change: done and not
        // done differ in shape as well as in hue.
        if (done) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .border(BorderStroke(2.dp, MaterialTheme.colorScheme.outline), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(number.toString(), style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (onClick != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (hint != null && !done) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LevelOption(name: String, example: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedCard(
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .heightIn(min = 56.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(8.dp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(
                    example,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The starting levels, for after the setup card has gone. They are a first-run
 * decision, so they no longer take up the top of the list; the menu is where to
 * go back to one. Custom is shown when nothing matches, since changing a single
 * switch moves you off every level and the menu should still say where you are.
 */
@Composable
fun QuickSettingsMenu(
    rules: RuleSet,
    screenLimits: Map<String, Limits>,
    onPreset: (String, Set<String>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // A preset rewrites every screen at once, so it is confirmed before the
    // change delay is even involved.
    var asking by remember { mutableStateOf<Pair<String, Set<String>>?>(null) }
    val held = heldScreens(rules, screenLimits)
    val presets = presetsFor(rules)
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.quick_settings),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                stringResource(R.string.quick_settings_heading),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            presets.forEach { (label, ids) ->
                // Here the last one is an action, so it says what it does; in
                // the setup card it is a level, and "Nothing" fits there.
                val name = stringResource(
                    if (label == R.string.preset_nothing) R.string.preset_allow_everything else label,
                )
                QuickSettingsItem(
                    name = name,
                    selected = held == ids,
                    onClick = {
                        open = false
                        asking = name to ids
                    },
                )
            }
            if (presets.none { it.second == held }) {
                // A state, not something to choose.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.preset_custom)) },
                    onClick = {},
                    enabled = false,
                    trailingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
                )
            }
        }
    }
    asking?.let { (name, ids) ->
        val changing = rules.apps.values.flatMap { it.screens }.count { (it.id in held) != (it.id in ids) }
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.quick_settings_confirm_title, name)) },
            text = {
                Text(pluralStringResource(R.plurals.quick_settings_confirm_body, changing, changing))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = null
                        onPreset(name, ids)
                    },
                ) {
                    Text(stringResource(R.string.quick_settings_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { asking = null }) {
                    Text(stringResource(R.string.action_cancel_change))
                }
            },
        )
    }
}

@Composable
private fun QuickSettingsItem(name: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(name) },
        onClick = onClick,
        trailingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null) }
        } else {
            null
        },
    )
}
