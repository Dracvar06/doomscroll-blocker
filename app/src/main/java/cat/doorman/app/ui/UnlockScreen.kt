package cat.doorman.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cat.doorman.app.R
import cat.doorman.app.rules.RuleSet

/**
 * The only way into a blocked screen, and it is deliberately not on the block
 * screen itself: you have to leave the app you are being kept out of, come
 * here, and sit through a wait that restarts if you wander off.
 */
@Composable
fun UnlockScreen(
    rules: RuleSet,
    selectedPackage: String?,
    secondsRemaining: Int,
    passMinutes: Int,
    wasReset: Boolean,
    onSelectPackage: (String) -> Unit,
    onGrant: () -> Unit,
    onBack: () -> Unit,
    labelFor: (String) -> String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.unlock_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.unlock_pick_app),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rules.apps.forEach { (packageName, app) ->
                FilterChip(
                    selected = packageName == selectedPackage,
                    onClick = { onSelectPackage(packageName) },
                    label = { Text(labelFor(app.labelKey)) },
                )
            }
        }

        if (selectedPackage != null) {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (wasReset) {
                        Text(
                            text = stringResource(R.string.unlock_reset),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (secondsRemaining > 0) {
                        Text(
                            text = stringResource(R.string.unlock_waiting, secondsRemaining),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            text = stringResource(R.string.unlock_leave_warning),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Button(onClick = onGrant) {
                            Text(pluralStringResource(R.plurals.unlock_grant, passMinutes, passMinutes))
                        }
                    }
                }
            }
        }

        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
    }
}
