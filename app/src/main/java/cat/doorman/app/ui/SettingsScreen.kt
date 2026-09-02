package cat.doorman.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cat.doorman.app.R
import cat.doorman.app.data.Prefs
import cat.doorman.app.limits.BlockMode
import cat.doorman.app.rules.RuleSet

/** An app the user has been seen using, offered for holding as a whole. */
data class OtherApp(val packageName: String, val label: String)

/**
 * One card per app, one switch per screen. Nothing is mandatory, including
 * whole apps: a screen someone did not ask to have blocked is the failure that
 * teaches people to bypass the app entirely.
 */
@Composable
fun BlockingSettings(
    rules: RuleSet,
    screenModes: Map<String, BlockMode>,
    appModes: Map<String, BlockMode>,
    otherApps: List<OtherApp>,
    allApps: List<OtherApp>,
    icons: AppIcons,
    remainingFor: (String) -> String?,
    pendingLabel: String?,
    pendingModeLabel: String?,
    pendingIsDelay: Boolean,
    pendingSeconds: Int,
    changeDelaySeconds: Int,
    onModeChosen: (String, BlockMode) -> Unit,
    onForgetApp: (String) -> Unit,
    onPreset: (Set<String>) -> Unit,
    onCancelPending: () -> Unit,
    onChangeDelay: (Int) -> Unit,
    labelFor: (String) -> String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.section_blocking),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.section_blocking_hint),
            style = MaterialTheme.typography.bodyMedium,
        )

        Text(
            text = stringResource(R.string.preset_label),
            style = MaterialTheme.typography.labelLarge,
        )
        // A change that is waiting to take effect, with the way to abandon it.
        if (pendingSeconds > 0) {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = when {
                            pendingIsDelay ->
                                stringResource(R.string.pending_change_delay, pendingSeconds)
                            pendingLabel != null && pendingModeLabel != null ->
                                stringResource(
                                    R.string.pending_change_mode,
                                    pendingLabel,
                                    pendingModeLabel,
                                    pendingSeconds,
                                )
                            pendingLabel != null ->
                                stringResource(R.string.pending_change_screen, pendingLabel, pendingSeconds)
                            else -> stringResource(R.string.pending_change_preset, pendingSeconds)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.pending_change_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onCancelPending) {
                        Text(stringResource(R.string.action_cancel_change))
                    }
                }
            }
        }

        val allScreens = rules.apps.values.flatMap { it.screens }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { onPreset(allScreens.map { it.id }.toSet()) },
                label = { Text(stringResource(R.string.preset_strict)) },
            )
            AssistChip(
                onClick = { onPreset(allScreens.filter { it.defaultEnabled }.map { it.id }.toSet()) },
                label = { Text(stringResource(R.string.preset_balanced)) },
            )
            AssistChip(
                onClick = { onPreset(emptySet()) },
                label = { Text(stringResource(R.string.preset_nothing)) },
            )
        }

        rules.apps.forEach { (packageName, app) ->
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = labelFor(app.labelKey),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    // The whole app, above the screens inside it: an allowance
                    // set here covers everything, and whichever runs out first
                    // is the one that stops you.
                    ModeRow(
                        label = stringResource(R.string.whole_app_row),
                        mode = appModes[packageName] ?: BlockMode.Off,
                        remaining = remainingFor(packageName),
                        onModeChosen = { onModeChosen(packageName, it) },
                    )
                    app.screens.forEach { screen ->
                        val mode = screenModes[screen.id] ?: BlockMode.Off
                        if (screen.supportsAllowance) {
                            ModeRow(
                                label = labelFor(screen.labelKey),
                                mode = mode,
                                remaining = remainingFor(screen.id),
                                onModeChosen = { onModeChosen(screen.id, it) },
                            )
                        } else {
                            SwitchRow(
                                label = labelFor(screen.labelKey),
                                checked = mode != BlockMode.Off,
                                onCheckedChange = {
                                    onModeChosen(
                                        screen.id,
                                        if (it) BlockMode.Blocked else BlockMode.Off,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.section_other_apps),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.section_other_apps_hint),
            style = MaterialTheme.typography.bodyMedium,
        )
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                var showAll by remember { mutableStateOf(false) }
                if (otherApps.isEmpty() && !showAll) {
                    Text(
                        text = stringResource(R.string.other_apps_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                // The apps you actually use come first and are usually all you
                // want. The rest are a tap away, for holding something before
                // you next open it.
                val shown = if (showAll) {
                    (otherApps + allApps).distinctBy { it.packageName }
                        .sortedBy { it.label.lowercase() }
                } else {
                    otherApps
                }
                shown.forEach { app ->
                    val mode = appModes[app.packageName] ?: BlockMode.Off
                    val icon by produceState<ImageBitmap?>(null, app.packageName) {
                        value = icons.load(app.packageName)
                    }
                    ModeRow(
                        label = app.label,
                        mode = mode,
                        remaining = remainingFor(app.packageName),
                        onModeChosen = { onModeChosen(app.packageName, it) },
                        icon = icon,
                        hasIcon = true,
                        // Only once it is switched off. Forgetting an app that
                        // is held would be an instant way to unhold it,
                        // straight past the wait every other loosening sits
                        // through.
                        onForget = if (
                            mode == BlockMode.Off &&
                            otherApps.any { it.packageName == app.packageName }
                        ) {
                            { onForgetApp(app.packageName) }
                        } else {
                            null
                        },
                    )
                }
                TextButton(onClick = { showAll = !showAll }) {
                    Text(
                        stringResource(
                            if (showAll) R.string.action_show_used_apps
                            else R.string.action_show_all_apps,
                        ),
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.section_change_delay),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.change_delay_explainer),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Prefs.CHANGE_DELAY_CHOICES.forEach { seconds ->
                FilterChip(
                    selected = seconds == changeDelaySeconds,
                    onClick = { onChangeDelay(seconds) },
                    label = {
                        Text(
                            when {
                                seconds == 0 -> stringResource(R.string.change_delay_instant)
                                seconds < 60 -> stringResource(R.string.change_delay_seconds, seconds)
                                else -> stringResource(R.string.change_delay_minutes, seconds / 60)
                            }
                        )
                    },
                )
            }
        }
    }
}
