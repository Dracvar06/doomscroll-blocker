package cat.doorman.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.Image
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnlockScreen(
    rules: RuleSet,
    icons: AppIcons,
    installedPackages: Set<String>,
    selectedPackage: String?,
    secondsRemaining: Int,
    totalSeconds: Int,
    passMinutes: Int,
    wasReset: Boolean,
    onSelectPackage: (String) -> Unit,
    onGrant: () -> Unit,
    onBack: () -> Unit,
    labelFor: (String) -> String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.unlock_title),
            style = MaterialTheme.typography.headlineSmall,
        )
    }
    Text(
        text = stringResource(R.string.unlock_pick_app),
        style = MaterialTheme.typography.titleMedium,
    )
    // FlowRow, not Row: the chips are one per supported app, and past four of
    // them a single row runs off the side of the screen, taking the app you
    // wanted with it.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // Only the apps on this phone: a pass for an app that is not installed
        // is a choice with nothing behind it. All of them if none is, so the
        // screen is never empty.
        val onPhone = rules.apps.filter { (pkg, app) -> app.packages(pkg).any { it in installedPackages } }
        onPhone.ifEmpty { rules.apps }.forEach { (packageName, app) ->
            val icon by produceState<ImageBitmap?>(null, packageName) {
                value = app.packages(packageName).firstNotNullOfOrNull { icons.load(it) }
            }
            val label = labelFor(app.labelKey)
            FilterChip(
                selected = packageName == selectedPackage,
                onClick = { onSelectPackage(packageName) },
                label = { Text(label) },
                leadingIcon = icon?.let { bitmap ->
                    {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                },
            )
        }
    }

    if (selectedPackage == null) return

    SurfaceCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (wasReset) {
                Text(
                    text = stringResource(R.string.unlock_reset),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (secondsRemaining > 0) {
                // The wait drawn as a ring that empties. A number alone said
                // how long was left but not how far along you were, and half
                // of a wait is easier to sit through when you can see it is
                // half.
                val fraction by animateFloatAsState(
                    targetValue = if (totalSeconds > 0) secondsRemaining / totalSeconds.toFloat() else 0f,
                    animationSpec = tween(durationMillis = 1_000),
                    label = "wait",
                )
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.size(160.dp),
                        strokeWidth = 8.dp,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                    Text(
                        text = stringResource(R.string.duration_seconds, secondsRemaining),
                        style = MaterialTheme.typography.displaySmall,
                    )
                }
                Text(
                    text = stringResource(R.string.unlock_leave_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
                    Text(pluralStringResource(R.plurals.unlock_grant, passMinutes, passMinutes))
                }
            }
        }
    }
}
