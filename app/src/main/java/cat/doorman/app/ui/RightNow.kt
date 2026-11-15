package cat.doorman.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cat.doorman.app.R
import cat.doorman.app.limits.Allowances
import cat.doorman.app.limits.HardTimes
import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.Tapers
import java.time.LocalDateTime

/**
 * Something that can be held, named for a sentence, with what holds it. [app]
 * is the app it belongs to, and [screen] says it is one screen of that app
 * rather than the whole of it.
 */
data class HeldTarget(
    val id: String,
    val name: String,
    val limits: Limits,
    val app: String = name,
    val screen: Boolean = false,
)

/** An app with something blocked: [screens] of its screens, or 0 for the whole app. */
data class BlockedApp(val name: String, val screens: Int)

/** A time limit that is running: [fraction] of it is used. */
data class RunningLimit(val name: String, val remainingMinutes: Int, val fraction: Float)

/** What is true about the held things at one moment. */
data class RightNowState(val blocked: List<BlockedApp>, val running: List<RunningLimit>)

/** Most names and bars shown before the rest are counted rather than listed. */
private const val MAX_NAMES = 3

/**
 * Works out what is blocked now and what has time left, from the same limits
 * and ledger the service decides on. Read-only: nothing here writes anything.
 *
 * A time limit that has run out counts as blocked, because it is: the cover is
 * up until the period turns.
 */
fun rightNow(
    targets: List<HeldTarget>,
    spent: Map<String, Allowances.Spent>,
    now: LocalDateTime,
    hard: HardTimes,
): RightNowState {
    // Per app, in the order the apps come: a whole-app block is 0 and
    // swallows its screens, since naming them adds nothing.
    val blocked = linkedMapOf<String, Int>()
    fun block(target: HeldTarget) {
        val soFar = blocked[target.app]
        blocked[target.app] = when {
            !target.screen || soFar == 0 -> 0
            else -> (soFar ?: 0) + 1
        }
    }
    val running = mutableListOf<RunningLimit>()
    targets.forEach { target ->
        val (id, name, limits) = target
        if (limits.blockedAt(now)) {
            block(target)
            return@forEach
        }
        val tightest = limits.allowancesOn(now.dayOfWeek).map { allowance ->
            val left = Allowances.remainingMillis(
                allowance,
                spent[Allowances.ledgerKey(id, allowance.period)] ?: Allowances.Spent.NOTHING,
                now,
                hard,
            )
            left to Tapers.effectiveMinutes(allowance, hard, now.toLocalDate()) * 60_000L
        }.minByOrNull { it.first } ?: return@forEach
        if (tightest.first <= 0L) {
            block(target)
        } else {
            running += RunningLimit(
                name = name,
                remainingMinutes = Math.ceil(tightest.first / 60_000.0).toInt(),
                fraction = 1f - tightest.first.toFloat() / tightest.second,
            )
        }
    }
    return RightNowState(blocked.map { (app, n) -> BlockedApp(app, n) }, running.sortedBy { it.remainingMinutes }.take(MAX_NAMES))
}

/**
 * The answer to "what is held right now, and how much do I have left", at the
 * top of the first tab. A state, not a setting: it only reads, and the list
 * below is where anything is changed.
 *
 * [actions] is a separate slot at the bottom for a row of things to do about
 * what is shown. It is empty until something belongs there.
 */
@Composable
fun RightNowCard(
    state: RightNowState,
    onGetPass: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    SurfaceCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.now_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.blocked.isEmpty() && state.running.isEmpty()) {
                Text(
                    stringResource(R.string.now_nothing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.blocked.isNotEmpty()) {
                val shown = state.blocked.take(MAX_NAMES).map {
                    if (it.screens == 0) it.name else stringResource(R.string.now_blocked_app_screens, it.name, it.screens)
                }.joinToString(", ")
                val extra = state.blocked.size - MAX_NAMES
                Text(
                    stringResource(
                        R.string.now_blocked,
                        if (extra > 0) pluralStringResource(R.plurals.now_blocked_apps_more, extra, shown, extra) else shown,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.running.forEach { limit ->
                // One node: the sentence already says what the bar shows, so
                // the bar is not announced as a second, unnamed control.
                Column(
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(
                            R.string.now_left,
                            limit.name,
                            stringResource(
                                R.string.remaining_left,
                                durationLabel(limit.remainingMinutes),
                            ),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { limit.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clearAndSetSemantics {},
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavRow(
            icon = Icons.Outlined.Lock,
            title = stringResource(R.string.action_get_pass),
            supporting = stringResource(R.string.pass_explainer),
            onClick = onGetPass,
        )
        actions()
    }
}

/**
 * The way back into the plan wizard, for the bottom of the Right now card.
 *
 * [nudge] adds a quiet line above it when the last weeks suggest the limits
 * are hard to keep. A line the person can wave away, not a banner, and never a
 * notification: being told off by an app about a limit is the quickest way to
 * stop opening it.
 */
@Composable
fun RethinkActions(nudge: Boolean, onRethink: () -> Unit, onDismissNudge: () -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    if (nudge) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.plan_nudge), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismissNudge) { Text(stringResource(R.string.action_not_now)) }
        }
    }
    NavRow(
        icon = Icons.Outlined.Edit,
        title = stringResource(R.string.plan_rethink_title),
        supporting = stringResource(R.string.plan_rethink_hint),
        onClick = onRethink,
    )
}
