package cat.doorman.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cat.doorman.app.R
import cat.doorman.app.gray.GraySetting
import cat.doorman.app.limits.Window
import cat.doorman.app.rules.RuleSet

/** The icon of every Grayscale row, on the home screen and on an app's page. */
@Composable
internal fun grayIcon(): ImageVector = ImageVector.vectorResource(R.drawable.ic_grayscale)

/**
 * Where grey is used, in a row of its own on the Blocks tab. It sits there
 * rather than in Settings because grey is something people do to a screen
 * while looking at their blocks, not a setting made once and lived with.
 */
@Composable
fun GrayscaleRow(count: Int, onClick: () -> Unit) {
    SurfaceCard {
        NavRow(
            icon = grayIcon(),
            title = stringResource(R.string.gray_title),
            supporting = if (count == 0) {
                stringResource(R.string.gray_none_yet)
            } else {
                pluralStringResource(R.plurals.gray_on_count, count, count)
            },
            onClick = onClick,
        )
    }
}

/**
 * Every app and where grey is on in it, in one place.
 *
 * Each app is listed with its whole-app state; under it only the screens that
 * have grey set, because the rest would be a long list of "in colour" that
 * hides the few that matter. Any other screen is made grey from its own page,
 * and the page says so.
 */
@Composable
fun GrayscaleOverview(
    rules: RuleSet,
    icons: AppIcons,
    installedPackages: Set<String>,
    graySettings: Map<String, GraySetting>,
    grayCanWrite: Boolean,
    changeDelaySeconds: Int,
    suggestions: List<Window>,
    onChange: (String, GraySetting?) -> Unit,
    onBack: () -> Unit,
    labelFor: (String) -> String,
) {
    // The id being edited and the name its editor is titled with.
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    val res = LocalResources.current
    SubPageHeader(title = stringResource(R.string.gray_title), onBack = onBack)
    SurfaceCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.gray_overview_explainer),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!grayCanWrite) PermissionHelp()
        }
    }

    // Apps that are not on the phone and have no grey are left out: rows that
    // can do nothing would sit between the ones that can.
    val shown = rules.apps.entries.filter { (packageName, app) ->
        app.packages(packageName).any { it in installedPackages } ||
            graySettings.containsKey(packageName) || app.screens.any { graySettings.containsKey(it.id) }
    }
    if (shown.isNotEmpty()) {
        SurfaceCard {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                shown.forEachIndexed { index, (packageName, app) ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val appName = labelFor(app.labelKey)
                    val icon by produceState<ImageBitmap?>(null, packageName) {
                        value = app.packages(packageName).firstNotNullOfOrNull { icons.load(it) }
                    }
                    GrayTargetRow(
                        label = appName,
                        summary = graySummaryText(res, graySettings[packageName]),
                        icon = {
                            AppAvatar(icon = icon, label = appName, size = 40.dp, loading = icon == null)
                        },
                        onClick = { editing = packageName to appName },
                    )
                    app.screens.filter { graySettings.containsKey(it.id) }.forEach { screen ->
                        val screenName = labelFor(screen.labelKey)
                        GrayTargetRow(
                            label = screenName,
                            summary = graySummaryText(res, graySettings[screen.id]),
                            // Under the app's name, not under its icon.
                            modifier = Modifier.padding(start = 52.dp),
                            onClick = { editing = screen.id to "$appName · $screenName" },
                        )
                    }
                }
            }
        }
    }
    Text(
        stringResource(R.string.gray_overview_other),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    editing?.let { (id, name) ->
        GrayEditor(
            title = stringResource(R.string.gray_editor_title, name),
            gray = graySettings[id],
            canWrite = grayCanWrite,
            suggestions = suggestions,
            changeDelaySeconds = changeDelaySeconds,
            onDismiss = { editing = null },
            onSave = { chosen ->
                onChange(id, chosen)
                editing = null
            },
        )
    }
}

/** One app or screen and its grey state, opening the small editor. */
@Composable
private fun GrayTargetRow(
    label: String,
    summary: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        // One node for a screen reader: the name, then its state.
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
