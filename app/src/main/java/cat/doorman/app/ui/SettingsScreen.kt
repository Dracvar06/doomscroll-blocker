package cat.doorman.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cat.doorman.app.R
import cat.doorman.app.rules.RuleSet

/**
 * One card per app, one switch per screen. Nothing is mandatory, including
 * whole apps: a screen someone did not ask to have blocked is the failure that
 * teaches people to bypass the app entirely.
 */
@Composable
fun BlockingSettings(
    rules: RuleSet,
    enabledScreenIds: Set<String>,
    onScreenToggled: (String, Boolean) -> Unit,
    onPreset: (Set<String>) -> Unit,
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

        rules.apps.forEach { (_, app) ->
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = labelFor(app.labelKey),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    app.screens.forEach { screen ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = labelFor(screen.labelKey),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Switch(
                                checked = screen.id in enabledScreenIds,
                                onCheckedChange = { onScreenToggled(screen.id, it) },
                            )
                        }
                    }
                }
            }
        }
    }
}
