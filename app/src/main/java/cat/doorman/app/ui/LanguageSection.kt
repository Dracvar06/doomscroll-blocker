package cat.doorman.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cat.doorman.app.R

/**
 * Doorman follows the phone's language on its own -- English is the default and
 * Catalan or Spanish appear automatically on a phone set to either. This picker
 * exists for the case the system cannot handle: someone whose phone is in one
 * language but who would rather read this app in another.
 *
 * Only Android 13 and later can set a language for a single app, so on older
 * versions the picker is replaced by a plain explanation rather than a control
 * that would not work.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguageSection(
    supported: Boolean,
    currentTag: String?,
    onPick: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.section_language),
            style = MaterialTheme.typography.titleLarge,
        )
        if (!supported) {
            Text(
                text = stringResource(R.string.language_follows_phone),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Language names are written in their own language, never
            // translated: someone hunting for "Español" should find that word,
            // whatever the app currently happens to be showing.
            val options = listOf(
                null to R.string.language_system,
                "en" to R.string.language_english,
                "ca" to R.string.language_catalan,
                "es" to R.string.language_spanish,
            )
            options.forEach { (tag, label) ->
                FilterChip(
                    selected = tag == currentTag,
                    onClick = { onPick(tag) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
    }
}
