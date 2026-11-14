package cat.doorman.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cat.doorman.app.R
import cat.doorman.app.gray.GraySetting
import cat.doorman.app.gray.GrayscaleController
import cat.doorman.app.limits.Window
import cat.doorman.app.rules.RuleSet

/**
 * Where the screen goes grey.
 *
 * Laid out like the blocks, app by app and screen by screen, because it is the
 * same question -- which of these -- and the answer is often a screen inside an
 * app and not the app: grey on the reels, in colour in your messages. Collapsed
 * by default; there are a lot of screens and few people want grey on most.
 *
 * When Doorman has not been given the permission it needs, the section says so
 * and shows the one line that fixes it, rather than offering switches that do
 * nothing.
 */
@Composable
fun GrayscaleSection(
    rules: RuleSet,
    settings: Map<String, GraySetting>,
    canWrite: Boolean,
    suggestions: List<Window>,
    labelFor: (String) -> String,
    onChange: (String, GraySetting?) -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.gray_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.gray_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (canWrite) {
            Text(
                stringResource(R.string.gray_ready),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            PermissionHelp()
        }
        rules.apps.forEach { (packageName, app) ->
            AppGray(
                appKey = packageName,
                appLabel = labelFor(app.labelKey),
                screens = app.screens.filter { it.match != null },
                settings = settings,
                suggestions = suggestions,
                labelFor = labelFor,
                onChange = onChange,
            )
        }
    }
}

/** The one command that lets Doorman change the screen's colours, ready to copy. */
@Composable
private fun PermissionHelp() {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Text(
        stringResource(R.string.gray_needs_permission),
        style = MaterialTheme.typography.bodyMedium,
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            GrayscaleController.GRANT_COMMAND,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(12.dp),
        )
    }
    OutlinedButton(
        onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("adb", GrayscaleController.GRANT_COMMAND))
            copied = true
        },
    ) {
        Text(
            stringResource(if (copied) R.string.gray_command_copied else R.string.gray_copy_command),
        )
    }
}

@Composable
private fun AppGray(
    appKey: String,
    appLabel: String,
    screens: List<cat.doorman.app.rules.ScreenRule>,
    settings: Map<String, GraySetting>,
    suggestions: List<Window>,
    labelFor: (String) -> String,
    onChange: (String, GraySetting?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val targets = listOf(appKey) + screens.map { it.id }
    val onCount = targets.count { it in settings }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { open = !open },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            appLabel,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (onCount > 0) {
            Text(
                pluralStringResource(R.plurals.gray_on_count, onCount, onCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open) {
        GrayRow(
            label = stringResource(R.string.whole_app_row),
            setting = settings[appKey],
            suggestions = suggestions,
            onChange = { onChange(appKey, it) },
        )
        screens.forEach { screen ->
            GrayRow(
                label = labelFor(screen.labelKey),
                setting = settings[screen.id],
                suggestions = suggestions,
                onChange = { onChange(screen.id, it) },
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** One screen or app: what it does now, and a way in to change it. */
@Composable
private fun GrayRow(
    label: String,
    setting: GraySetting?,
    suggestions: List<Window>,
    onChange: (GraySetting?) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val res = LocalResources.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 16.dp)
            .clickable { editing = true },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            graySummaryText(res, setting),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false),
            color = if (setting == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (editing) {
        GrayscaleEditor(
            title = label,
            setting = setting,
            suggestions = suggestions,
            onDismiss = { editing = false },
            onSave = {
                onChange(it)
                editing = false
            },
        )
    }
}

/** "In colour", "Grey", or the hours it is grey in. */
fun graySummaryText(res: Resources, setting: GraySetting?): String = when {
    setting == null -> res.getString(R.string.gray_row_off)
    setting.windows.isEmpty() -> res.getString(R.string.gray_row_always)
    else -> setting.windows.joinToString(" · ") { window ->
        val times = "${formatMinute(window.fromMinute)}–${formatMinute(window.toMinute)}"
        daysText(res, window.days)?.let { res.getString(R.string.mode_on_days, times, it) } ?: times
    }
}

/** In colour or grey, and when. Reuses the hours editor the limits use. */
@Composable
private fun GrayscaleEditor(
    title: String,
    setting: GraySetting?,
    suggestions: List<Window>,
    onDismiss: () -> Unit,
    onSave: (GraySetting?) -> Unit,
) {
    var draft by remember { mutableStateOf(setting) }
    EditorDialog(title = title, onDismiss = onDismiss, onSave = { onSave(draft) }) {
        Text(
            stringResource(R.string.gray_editor_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = draft == null,
                onClick = { draft = null },
                label = { Text(stringResource(R.string.gray_choice_colour)) },
            )
            FilterChip(
                selected = draft != null,
                onClick = { if (draft == null) draft = GraySetting() },
                label = { Text(stringResource(R.string.gray_choice_grey)) },
            )
        }
        val current = draft
        if (current != null) {
            WindowsEditor(
                windows = current.windows,
                heading = stringResource(R.string.gray_hours_heading),
                hint = stringResource(R.string.gray_hours_hint),
                addLabel = stringResource(R.string.gray_add_hours),
                suggestions = suggestions,
                onChange = { draft = current.copy(windows = it) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
