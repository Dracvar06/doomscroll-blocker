package cat.doorman.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.Text
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
import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.Window
import cat.doorman.app.rules.RuleLoader
import cat.doorman.app.rules.RuleSet

/** An app the user has been seen using, offered for holding as a whole. */
data class OtherApp(val packageName: String, val label: String)

/**
 * One card per app, one switch per screen. Nothing is mandatory, including
 * whole apps: a screen someone did not ask to have blocked is the failure that
 * teaches people to bypass the app entirely.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    onLimitsChosen: (String, Limits) -> Unit,
    onPreset: (Set<String>) -> Unit,
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
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.section_blocking_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val allScreens = rules.apps.values.flatMap { it.screens }
        // Four rather than three. The old set went "everything", "the usual",
        // "nothing", and the gap between the last two was a cliff: anyone who
        // found the balanced setting too much had nowhere left to go but off.
        val presets = listOf(
            R.string.preset_strict to allScreens.map { it.id }.toSet(),
            R.string.preset_balanced to allScreens.filter { it.defaultEnabled }.map { it.id }.toSet(),
            R.string.preset_permissive to RuleLoader.endlessScreenIds(rules),
            R.string.preset_nothing to emptySet(),
        )
        // The preset that matches what is set now is shown as chosen. They
        // used to be four buttons with no state, so nobody could tell which
        // one they were on -- or that changing a single switch had taken them
        // off all of them, which is what no chip lit up now says.
        val enabled = allScreens.filter { !(screenLimits[it.id] ?: Limits.OFF).isOff }
            .map { it.id }.toSet()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (label, ids) ->
                FilterChip(
                    selected = enabled == ids,
                    onClick = { onPreset(ids) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        Text(
            text = stringResource(R.string.preset_permissive_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        rules.apps.forEach { (packageName, app) ->
            // Installed apps open, absent ones closed, unless the user has
            // said otherwise. Someone without TikTok should not have to scroll
            // past a screenful of its settings to reach the app they do have.
            val installed = app.packages(packageName).any { it in installedPackages }
            val open = openCards[packageName] ?: installed
            val held = app.screens.count { !(screenLimits[it.id] ?: Limits.OFF).isOff }
            val appIcon by produceState<ImageBitmap?>(null, packageName) {
                value = app.packages(packageName)
                    .firstNotNullOfOrNull { icons.load(it) }
            }
            val label = labelFor(app.labelKey)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCardOpenChanged(packageName, !open) }
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppAvatar(icon = appIcon, label = label, size = 32.dp, loading = installed)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    // A closed card still says whether it is doing anything.
                    // Collapsing should tidy the screen, not hide whether an
                    // app is being held. Accent means "this is doing
                    // something"; the colour is what lets you find it without
                    // reading every row.
                    if (!open) {
                        val doingSomething = held > 0 && installed
                        Text(
                            text = when {
                                // An app that is not on the phone says so,
                                // rather than reporting nothing blocked as if
                                // that were a choice someone had made.
                                !installed -> stringResource(R.string.app_not_installed)
                                // Zero is not a plural category in any of the
                                // three languages, so it needs its own string.
                                held == 0 -> stringResource(R.string.screens_held_none)
                                else -> pluralStringResource(R.plurals.screens_held, held, held)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (doingSomething) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    // An arrow, not the word "Hide": a word reads as a
                    // setting called Hide, and an arrow is what every
                    // expanding list on the phone already uses.
                    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(
                            if (open) R.string.card_close else R.string.card_open,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.rotate(turn),
                    )
                }
                AnimatedVisibility(visible = open) {
                    Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, bottom = 8.dp)) {
                        // The whole app, above the screens inside it: an
                        // allowance set here covers everything, and whichever
                        // runs out first is the one that stops you.
                        LimitRow(
                            label = stringResource(R.string.whole_app_row),
                            limits = appLimits[packageName] ?: Limits.OFF,
                            remaining = remainingFor(packageName),
                            onLimitsChosen = { onLimitsChosen(packageName, it) },
                            help = stringResource(R.string.help_whole_app),
                            suggestions = knownWindows,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
        }

        Text(
            text = stringResource(R.string.section_other_apps),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = stringResource(R.string.section_other_apps_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                var query by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.other_apps_search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
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
    }
}
