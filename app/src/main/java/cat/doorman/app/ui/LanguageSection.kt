package cat.doorman.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cat.doorman.app.R

/**
 * Doorman follows the phone's language on its own -- English is the default and
 * Catalan or Spanish appear automatically on a phone set to either. This picker
 * exists for the case the system cannot handle: someone whose phone is in one
 * language but who would rather read this app in another.
 *
 * One row saying which language is in use, opening a list to choose from --
 * the way Android's own settings offer a single choice. It used to be four
 * chips, which is four buttons for a decision made once, wrapping onto a
 * second line and growing a line with every language added.
 *
 * Only Android 13 and later can set a language for a single app, so on older
 * versions the row is replaced by a plain explanation rather than a control
 * that would not work.
 */
@Composable
fun LanguageSection(
    supported: Boolean,
    currentTag: String?,
    onPick: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!supported) {
        Column(modifier = modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.section_language),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.language_follows_phone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    // Language names are written in their own language, never translated:
    // someone hunting for "Español" should find that word, whatever the app
    // currently happens to be showing.
    val options = listOf(
        null to R.string.language_system,
        "en" to R.string.language_english,
        "ca" to R.string.language_catalan,
        "es" to R.string.language_spanish,
    )
    val currentLabel = options.firstOrNull { it.first == currentTag }?.second
        ?: R.string.language_system
    var choosing by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { choosing = true }
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.section_language),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(currentLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.section_language)) },
            text = {
                Column(modifier = Modifier.selectableGroup()) {
                    options.forEach { (tag, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = tag == currentTag,
                                    role = Role.RadioButton,
                                    onClick = {
                                        choosing = false
                                        // Picking the one already in use does
                                        // nothing, rather than recreating the
                                        // screen for no change.
                                        if (tag != currentTag) onPick(tag)
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = tag == currentTag, onClick = null)
                            Spacer(Modifier.width(16.dp))
                            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosing = false }) {
                    Text(stringResource(R.string.action_cancel_change))
                }
            },
        )
    }
}
