package cat.doorman.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cat.doorman.app.R
import cat.doorman.app.data.SetupStage

/**
 * Three things to do before Doorman is any use, at the top of Blocks until they
 * are done or waved away.
 *
 * A checklist rather than a tour: the old walkthrough explained, and then left
 * people on a red card to work out the order for themselves. Each item does its
 * thing from here. The last is optional and does not hold the card open.
 */
@Composable
fun SetupCard(
    serviceOn: Boolean,
    stage: SetupStage,
    notificationsOn: Boolean,
    onEnable: () -> Unit,
    onMakePlan: () -> Unit,
    onNotify: () -> Unit,
    onDismiss: () -> Unit,
) {
    SurfaceCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.setup_title),
                style = MaterialTheme.typography.titleLarge,
            )

            SetupStep(
                number = 1,
                done = serviceOn,
                title = stringResource(R.string.action_enable_doorman),
                hint = stringResource(R.string.setup_step_enable_hint),
                onClick = if (serviceOn) null else onEnable,
            )

            // Done once a plan has been put in place, not once it is asked
            // for: before that the limits are whatever shipped, and a card
            // with an answer already ticked is a question nobody read.
            SetupStep(
                number = 2,
                done = stage != SetupStage.OPEN,
                title = stringResource(R.string.setup_step_plan),
                hint = stringResource(R.string.setup_step_plan_hint),
                onClick = if (stage != SetupStage.OPEN) null else onMakePlan,
            )

            SetupStep(
                number = 3,
                done = notificationsOn,
                title = stringResource(R.string.setup_step_notify),
                hint = null,
                onClick = if (notificationsOn) null else onNotify,
            )

            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.setup_dismiss))
            }
        }
    }
}

/** A numbered row that becomes a tick once it is done. */
@Composable
private fun SetupStep(
    number: Int,
    done: Boolean,
    title: String,
    hint: String?,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A tick against a numbered ring, not a colour change: done and not
        // done differ in shape as well as in hue.
        if (done) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .border(BorderStroke(2.dp, MaterialTheme.colorScheme.outline), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(number.toString(), style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (onClick != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (hint != null && !done) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
