package cat.doorman.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cat.doorman.app.R
import cat.doorman.app.gray.GrayPolicy
import cat.doorman.app.gray.GraySetting
import cat.doorman.app.gray.GrayscaleController
import cat.doorman.app.limits.Window

/**
 * The one command that lets Doorman change the screen's colours, ready to copy.
 *
 * Shown on the Grayscale page, and from the Colour controls, when Doorman has
 * not been given the permission it needs, rather than offering switches that
 * do nothing.
 */
@Composable
internal fun PermissionHelp() {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("adb", GrayscaleController.GRANT_COMMAND),
                )
                copied = true
            },
        ) {
            Text(
                stringResource(
                    if (copied) R.string.gray_command_copied else R.string.gray_copy_command,
                ),
            )
        }
    }
}

/** "In colour", "Grey", or "Grey" and the hours it is grey in. */
fun graySummaryText(res: Resources, setting: GraySetting?): String = when {
    setting == null -> res.getString(R.string.gray_row_off)
    setting.windows.isEmpty() -> res.getString(R.string.colour_summary_grey)
    else -> res.getString(
        R.string.colour_summary_hours,
        setting.windows.joinToString(" · ") { window ->
            val times = "${formatMinute(window.fromMinute)}–${formatMinute(window.toMinute)}"
            daysText(res, window.days)?.let { res.getString(R.string.mode_on_days, times, it) }
                ?: times
        },
    )
}

private enum class ColourMode {
    NORMAL, GREY, HOURS;

    companion object {
        fun of(setting: GraySetting?): ColourMode = when {
            setting == null -> NORMAL
            setting.windows.isEmpty() -> GREY
            else -> HOURS
        }
    }
}

/**
 * Normal, grey all day, or grey at set hours.
 *
 * Without the permission it needs, Doorman cannot change the screen's colours,
 * so this says what is missing and how to give it rather than offering a
 * control that does nothing. A grey setting that is already saved still shows,
 * because hiding it would leave it on with no way to see or remove it.
 *
 * Shared by the screen editor's Colour section and by the small grey editor, so
 * the two cannot drift apart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColourControls(
    grey: GraySetting?,
    canWrite: Boolean,
    suggestions: List<Window>,
    onChange: (GraySetting?) -> Unit,
) {
    // Kept apart from the setting: hours with the last of them removed saves as
    // grey all day, and the control should not jump to Grey under the finger
    // that is about to add one back.
    var mode by remember { mutableStateOf(ColourMode.of(grey)) }
    Text(
        stringResource(R.string.gray_editor_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!canWrite) {
        var showHow by remember { mutableStateOf(false) }
        SurfaceCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.colour_setup_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                OutlinedButton(onClick = { showHow = true }) {
                    Text(stringResource(R.string.colour_setup_how))
                }
            }
        }
        if (showHow) {
            ModalBottomSheet(onDismissRequest = { showHow = false }) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
                    PermissionHelp()
                }
            }
        }
    }
    if (canWrite || grey != null) {
        ChoiceRow(
            options = listOf(
                ColourMode.NORMAL to stringResource(R.string.colour_normal),
                ColourMode.GREY to stringResource(R.string.colour_grey),
                ColourMode.HOURS to stringResource(R.string.colour_grey_hours),
            ),
            selected = mode,
            onSelect = { chosen ->
                mode = chosen
                onChange(
                    when (chosen) {
                        ColourMode.NORMAL -> null
                        ColourMode.GREY -> GraySetting()
                        // Starts on the hours most people mean, so choosing it
                        // does not leave the user looking at an empty list that
                        // quietly means grey all day.
                        ColourMode.HOURS -> GraySetting(
                            grey?.windows.orEmpty().ifEmpty { listOf(DEFAULT_WINDOW) },
                        )
                    },
                )
            },
        )
    }
    if (mode == ColourMode.HOURS && grey != null) {
        WindowsEditor(
            windows = grey.windows,
            heading = null,
            hint = stringResource(R.string.gray_hours_hint),
            addLabel = stringResource(R.string.gray_add_hours),
            suggestions = suggestions,
            onChange = { onChange(grey.copy(windows = it)) },
        )
    }
}

/**
 * Grey on its own, for one app or screen, without the limits editor around it.
 *
 * Saves through the same path as the editor, so tightening lands at once and
 * loosening waits; it says so before Save, as the editor does.
 */
@Composable
internal fun GrayEditor(
    title: String,
    gray: GraySetting?,
    canWrite: Boolean,
    suggestions: List<Window>,
    changeDelaySeconds: Int,
    onDismiss: () -> Unit,
    onSave: (GraySetting?) -> Unit,
) {
    var draft by remember { mutableStateOf(gray) }
    val res = LocalResources.current
    val changed = draft != gray
    val waits = changed && changeDelaySeconds > 0 && GrayPolicy.isLoosening(gray, draft)
    EditorDialog(
        title = title,
        changed = changed,
        onDismiss = onDismiss,
        onSave = { onSave(draft) },
    ) {
        SurfaceCard(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(graySummaryText(res, draft), style = MaterialTheme.typography.titleMedium)
                if (waits) {
                    Text(
                        stringResource(R.string.limits_applies_in, changeDelaySeconds),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        ColourControls(
            grey = draft,
            canWrite = canWrite,
            suggestions = suggestions,
            onChange = { draft = it },
        )
        Spacer(Modifier.height(24.dp))
    }
}
