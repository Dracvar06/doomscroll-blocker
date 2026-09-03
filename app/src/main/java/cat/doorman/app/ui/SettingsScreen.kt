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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
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
import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.Window
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
    screenLimits: Map<String, Limits>,
    appLimits: Map<String, Limits>,
    otherApps: List<OtherApp>,
    icons: AppIcons,
    installedPackages: Set<String>,
    openCards: Map<String, Boolean>,
    onCardOpenChanged: (String, Boolean) -> Unit,
    remainingFor: (String) -> String?,
    pendingLabel: String?,
    pendingModeLabel: String?,
    pendingIsDelay: Boolean,
    pendingSeconds: Int,
    changeDelaySeconds: Int,
    onLimitsChosen: (String, Limits) -> Unit,
    onPreset: (Set<String>) -> Unit,
    onCancelPending: () -> Unit,
    onChangeDelay: (Int) -> Unit,
    labelFor: (String) -> String,
    helpFor: (String?) -> String?,
) {
    // Every set of hours the user has already chosen anywhere, offered back to
    // them in each editor so the same two times are not picked out of a clock
    // face once per app.
    val knownWindows: List<Window> = remember(screenLimits, appLimits) {
        (screenLimits.values + appLimits.values).flatMap { it.windows }.distinct()
    }
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
                    // Installed apps open, absent ones closed, unless the user
                    // has said otherwise. Someone without TikTok should not have
                    // to scroll past a screenful of its settings to reach the
                    // app they do have.
                    val installed = app.packages(packageName).any { it in installedPackages }
                    val open = openCards[packageName] ?: installed
                    val held = app.screens.count { !(screenLimits[it.id] ?: Limits.OFF).isOff }
                    val appIcon by produceState<ImageBitmap?>(null, packageName) {
                        value = app.packages(packageName)
                            .firstNotNullOfOrNull { icons.load(it) }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCardOpenChanged(packageName, !open) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                appIcon?.let {
                                    Image(
                                        bitmap = it,
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.size(32.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = labelFor(app.labelKey),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        // A closed card still says whether it is doing
                        // anything. Collapsing should tidy the screen, not
                        // hide whether an app is being held.
                        Text(
                            text = when {
                                open -> stringResource(R.string.card_close)
                                // An app that is not on the phone says so,
                                // rather than reporting nothing blocked as if
                                // that were a choice someone had made.
                                !installed -> stringResource(R.string.app_not_installed)
                                // Zero is not a plural category in any of the
                                // three languages, so it needs its own string
                                // rather than a quantity nobody would ever see.
                                held == 0 -> stringResource(R.string.screens_held_none)
                                else -> pluralStringResource(R.plurals.screens_held, held, held)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (!open) return@Column
                    // The whole app, above the screens inside it: an allowance
                    // set here covers everything, and whichever runs out first
                    // is the one that stops you.
                    LimitRow(
                        label = stringResource(R.string.whole_app_row),
                        limits = appLimits[packageName] ?: Limits.OFF,
                        remaining = remainingFor(packageName),
                        onLimitsChosen = { onLimitsChosen(packageName, it) },
                        help = stringResource(R.string.help_whole_app),
                        suggestions = knownWindows,
                    )
                    app.screens.forEach { screen ->
                        val limit = screenLimits[screen.id] ?: Limits.OFF
                        if (screen.supportsAllowance) {
                            LimitRow(
                                label = labelFor(screen.labelKey),
                                limits = limit,
                                remaining = remainingFor(screen.id),
                                onLimitsChosen = { onLimitsChosen(screen.id, it) },
                                help = helpFor(screen.helpKey),
                                suggestions = knownWindows,
                            )
                        } else {
                            SwitchRow(
                                label = labelFor(screen.labelKey),
                                checked = !limit.isOff,
                                onCheckedChange = {
                                    onLimitsChosen(
                                        screen.id,
                                        if (it) Limits.BLOCKED else Limits.OFF,
                                    )
                                },
                                help = helpFor(screen.helpKey),
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
                var query by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.other_apps_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                // Held apps first and always, then whatever the search finds.
                // Every app on the phone laid out at once is a wall nobody
                // reads, and the list someone actually cares about is the
                // handful they have already chosen.
                val held = otherApps.filter { !(appLimits[it.packageName] ?: Limits.OFF).isOff }
                val matches = if (query.isBlank()) {
                    emptyList()
                } else {
                    otherApps.filter {
                        it.label.contains(query, ignoreCase = true) &&
                            it !in held
                    }
                }
                if (held.isEmpty() && query.isBlank()) {
                    Text(
                        text = stringResource(R.string.other_apps_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                (held + matches).forEach { app ->
                    val limit = appLimits[app.packageName] ?: Limits.OFF
                    val icon by produceState<ImageBitmap?>(null, app.packageName) {
                        value = icons.load(app.packageName)
                    }
                    LimitRow(
                        label = app.label,
                        limits = limit,
                        remaining = remainingFor(app.packageName),
                        onLimitsChosen = { onLimitsChosen(app.packageName, it) },
                        icon = icon,
                        hasIcon = true,
                        help = stringResource(R.string.help_whole_app),
                        suggestions = knownWindows,
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
