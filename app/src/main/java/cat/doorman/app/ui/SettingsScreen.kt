package cat.doorman.app.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cat.doorman.app.R
import cat.doorman.app.gray.GraySetting
import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.Window
import cat.doorman.app.rules.AppRules
import cat.doorman.app.rules.RuleSet

/** An app the user has been seen using, offered for holding as a whole. */
data class OtherApp(val packageName: String, val label: String)

/**
 * One row per app, a page per app. Nothing is mandatory, including whole apps:
 * a screen someone did not ask to have blocked is the failure that teaches
 * people to bypass the app entirely.
 *
 * Eight open cards were a screen of switches to scroll through to find the one
 * app being looked after. A row each says what every app is doing, and the
 * switches wait one tap away.
 */
@Composable
fun AppList(
    rules: RuleSet,
    screenLimits: Map<String, Limits>,
    appLimits: Map<String, Limits>,
    graySettings: Map<String, GraySetting>,
    otherApps: List<OtherApp>,
    icons: AppIcons,
    installedPackages: Set<String>,
    remainingFor: (String) -> String?,
    onOpen: (String) -> Unit,
    labelFor: (String) -> String,
) {
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

        // Fixed when the list first has the user's choices, not on every change:
        // an app whose last blocked screen is switched off would otherwise drop
        // out from under the finger that just saved it. Apps with something
        // blocked come first, because they are the ones being looked after.
        val loaded = screenLimits.isNotEmpty()
        val (onPhone, notOnPhone) = remember(loaded) {
            rules.apps.entries
                .sortedByDescending { (_, app) ->
                    app.screens.any { !(screenLimits[it.id] ?: Limits.OFF).isOff }
                }
                .partition { (packageName, app) ->
                    app.packages(packageName).any { it in installedPackages }
                }
        }
        val builtIn: @Composable (Map.Entry<String, AppRules>) -> Unit = { (packageName, app) ->
            val installed = app.packages(packageName).any { it in installedPackages }
            val appIcon by produceState<ImageBitmap?>(null, packageName) {
                value = app.packages(packageName).firstNotNullOfOrNull { icons.load(it) }
            }
            // The whole app first, as on its page: whichever limit runs out
            // first is the one that stops you, and the first to have a clock
            // is the one worth naming.
            val targets = listOf(
                Triple(packageName, stringResource(R.string.whole_app_row), appLimits[packageName]),
            ) + app.screens.filterNot { it.messages }.map {
                Triple(it.id, labelFor(it.labelKey), screenLimits[it.id])
            }
            val wholeGray = graySettings[packageName]
            AppRow(
                label = labelFor(app.labelKey),
                summary = appSummary(
                    installed = installed,
                    held = targets.count { !(it.third ?: Limits.OFF).isOff },
                    limited = targets.firstNotNullOfOrNull { (id, name, limits) ->
                        remainingFor(id)?.takeIf { !(limits ?: Limits.OFF).isOff }
                            ?.let { "$name · $it" }
                    },
                    // In words, as on the app's page: the whole app being grey
                    // is as much of what it is doing as a block is.
                    gray = wholeGray?.let { graySummaryText(LocalResources.current, it) },
                ),
                // Accent means "this is doing something"; it is what lets you
                // find the held apps without reading every row.
                active = installed &&
                    (wholeGray != null || targets.any { !(it.third ?: Limits.OFF).isOff }),
                icon = appIcon,
                loading = installed,
                onClick = { onOpen(packageName) },
            )
        }

        SurfaceCard {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                var adding by rememberSaveable { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clickable(role = Role.Button) { adding = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Where an app's icon would be, so the names line up.
                    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.apps_add),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (adding) {
                    AddAppDialog(
                        apps = otherApps,
                        icons = icons,
                        onDismiss = { adding = false },
                        onPick = { pkg -> adding = false; onOpen(pkg) },
                    )
                }
                onPhone.forEach {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    builtIn(it)
                }
                // Held apps only, and after the ones with rules of their own.
                // Every app on the phone laid out at once is a wall nobody
                // reads; the list someone cares about is what they chose.
                otherApps.filter { !(appLimits[it.packageName] ?: Limits.OFF).isOff }.forEach { app ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val icon by produceState<ImageBitmap?>(null, app.packageName) {
                        value = icons.load(app.packageName)
                    }
                    AppRow(
                        label = app.label,
                        summary = listOfNotNull(
                            limitsSummaryText(
                                LocalResources.current,
                                appLimits.getValue(app.packageName),
                                LocalHardTimes.current,
                            ),
                            remainingFor(app.packageName),
                        ).joinToString(" · "),
                        active = true,
                        icon = icon,
                        loading = icon == null,
                        onClick = { onOpen(app.packageName) },
                    )
                }
            }
        }

        if (notOnPhone.isNotEmpty()) {
            // Apps that are not here would otherwise be a screenful of rows
            // that can do nothing, between the ones that can.
            var showAbsent by rememberSaveable { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAbsent = !showAbsent }
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.apps_not_on_phone),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (showAbsent) R.string.card_close else R.string.card_open,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(if (showAbsent) 180f else 0f),
                )
            }
            if (showAbsent) {
                SurfaceCard {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        notOnPhone.forEachIndexed { index, entry ->
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            builtIn(entry)
                        }
                    }
                }
            }
        }
    }
}

/**
 * What an app row says under its name.
 *
 * An app that is not on the phone says so, rather than reporting nothing
 * blocked as if that were a choice someone had made. Zero is not a plural
 * category in any of the three languages, so "nothing" is a string of its own.
 */
@Composable
private fun appSummary(installed: Boolean, held: Int, limited: String?, gray: String?): String = when {
    !installed -> stringResource(R.string.app_not_installed)
    else -> listOfNotNull(
        if (held == 0) {
            stringResource(R.string.screens_held_none)
        } else {
            pluralStringResource(R.plurals.apps_blocked_count, held, held)
        },
        limited,
        gray,
    ).joinToString(" · ")
}

/** One app in the list: icon, name, what it is doing, and an arrow. */
@Composable
private fun AppRow(
    label: String,
    summary: String,
    active: Boolean,
    icon: ImageBitmap?,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        // One node for a screen reader: the name, then what it is doing.
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppAvatar(icon = icon, label = label, size = 40.dp, loading = loading)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Searching the phone's apps for one to hold. Nothing is listed until something
 * is typed: the whole phone at once is a wall, and somebody who has come here
 * knows the name of what they want.
 */
@Composable
private fun AddAppDialog(
    apps: List<OtherApp>,
    icons: AppIcons,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query, apps) {
        // The package name too: on a Catalan phone Play Books is "Play
        // Llibres", and someone typing "books" should still find it.
        val q = query.trim()
        if (q.isEmpty()) {
            emptyList()
        } else {
            apps.filter {
                it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apps_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    label = { Text(stringResource(R.string.other_apps_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.packageName }) { app ->
                        val icon by produceState<ImageBitmap?>(null, app.packageName) {
                            value = icons.load(app.packageName)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable(role = Role.Button) { onPick(app.packageName) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppAvatar(icon = icon, label = app.label, size = 40.dp, loading = icon == null)
                            Spacer(Modifier.width(16.dp))
                            Text(
                                app.label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel_change)) }
        },
    )
}

/**
 * One app's page: the whole app, then each of its screens, each opening its
 * own editor. An app without rules of its own has only the whole-app row.
 */
@Composable
fun AppDetail(
    packageName: String,
    rules: RuleSet,
    screenLimits: Map<String, Limits>,
    appLimits: Map<String, Limits>,
    graySettings: Map<String, GraySetting>,
    grayCanWrite: Boolean,
    changeDelaySeconds: Int,
    otherApps: List<OtherApp>,
    remainingFor: (String) -> String?,
    onChosen: (String, Limits, GraySetting?) -> Unit,
    onGrayChosen: (String, GraySetting?) -> Unit,
    onBack: () -> Unit,
    labelFor: (String) -> String,
    helpFor: (String?) -> String?,
) {
    val knownWindows = remember(screenLimits, appLimits, graySettings) {
        knownWindows(screenLimits, appLimits, graySettings)
    }
    val app = rules.apps[packageName]
    val appName = app?.let { labelFor(it.labelKey) }
        ?: otherApps.firstOrNull { it.packageName == packageName }?.label
        ?: packageName
    SubPageHeader(title = appName, onBack = onBack)
    // Above the screens, always shown: grey was once the last control of each
    // screen's editor, and nobody scrolled that far to look for it. An app
    // the rules do not name has nothing for grey to follow, so no row.
    if (app != null) {
        var editingGray by remember { mutableStateOf(false) }
        SurfaceCard {
            NavRow(
                icon = grayIcon(),
                title = stringResource(R.string.gray_title),
                supporting = graySummaryText(LocalResources.current, graySettings[packageName]),
                onClick = { editingGray = true },
            )
        }
        if (editingGray) {
            GrayEditor(
                title = stringResource(R.string.gray_editor_title, appName),
                gray = graySettings[packageName],
                canWrite = grayCanWrite,
                suggestions = knownWindows,
                changeDelaySeconds = changeDelaySeconds,
                onDismiss = { editingGray = false },
                onSave = { chosen ->
                    onGrayChosen(packageName, chosen)
                    editingGray = false
                },
            )
        }
    }
    SurfaceCard {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            // The whole app, above the screens inside it: an allowance set
            // here covers everything, and whichever runs out first is the one
            // that stops you.
            LimitRow(
                label = stringResource(R.string.whole_app_row),
                limits = appLimits[packageName] ?: Limits.OFF,
                remaining = remainingFor(packageName),
                onSaved = { chosen, chosenGray -> onChosen(packageName, chosen, chosenGray) },
                help = stringResource(R.string.help_whole_app),
                suggestions = knownWindows,
                gray = graySettings[packageName],
                supportsGrey = app != null,
                grayCanWrite = grayCanWrite,
                changeDelaySeconds = changeDelaySeconds,
            )
            Text(
                text = stringResource(R.string.app_detail_whole_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (app != null) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            app?.screens?.filterNot { it.messages }?.forEach { screen ->
                LimitRow(
                    label = labelFor(screen.labelKey),
                    limits = screenLimits[screen.id] ?: Limits.OFF,
                    remaining = remainingFor(screen.id),
                    onSaved = { chosen, chosenGray -> onChosen(screen.id, chosen, chosenGray) },
                    help = helpFor(screen.helpKey),
                    suggestions = knownWindows,
                    reelsAllowed = screen.countsReels,
                    supportsAllowance = screen.supportsAllowance,
                    gray = graySettings[screen.id],
                    // Grey follows a screen the rules can recognise; one they
                    // only name has nothing for it to follow.
                    supportsGrey = screen.match != null,
                    grayCanWrite = grayCanWrite,
                    changeDelaySeconds = changeDelaySeconds,
                )
            }
        }
    }
}

/**
 * Every set of hours the user has already chosen anywhere, for offering back in
 * an editor so the same two times are not picked out of a clock face once per
 * app.
 */
internal fun knownWindows(
    screenLimits: Map<String, Limits>,
    appLimits: Map<String, Limits>,
    graySettings: Map<String, GraySetting>,
): List<Window> = (
    (screenLimits.values + appLimits.values).flatMap { it.windows } +
        graySettings.values.flatMap { it.windows }
    ).distinct()
