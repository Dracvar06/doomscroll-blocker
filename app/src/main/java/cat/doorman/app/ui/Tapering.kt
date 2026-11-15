package cat.doorman.app.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cat.doorman.app.R
import cat.doorman.app.limits.Allowance
import cat.doorman.app.limits.DialScale
import cat.doorman.app.limits.HardTimes
import cat.doorman.app.limits.Taper
import cat.doorman.app.limits.Tapers
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * The hard days and weeks taken so far, for anything on screen that says what
 * a limit is worth. A local rather than a parameter on every row and sentence:
 * they all read the same value, and one forgotten caller would have shown the
 * starting minutes of a limit that has been lowering for weeks.
 */
val LocalHardTimes = compositionLocalOf { HardTimes.EMPTY }

/** [date] in the phone's own long date format. */
fun dateText(context: Context, date: LocalDate): String =
    android.text.format.DateFormat.getLongDateFormat(context)
        .format(Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()))

/**
 * [this] set to [minutes], from [today]. A taper starts over from there: the
 * minutes are the level it is counted down from, and a goal the new level is
 * not above leaves nothing to lower towards, so the taper goes.
 */
internal fun Allowance.restartedAt(minutes: Int, today: LocalDate): Allowance {
    val taper = taper ?: return copy(minutes = minutes)
    if (taper.goalMinutes >= minutes) return copy(minutes = minutes, taper = null)
    return copy(minutes = minutes, taper = taper.copy(startedOn = today))
}

/** How fast a limit lowers, as a share of the level it is at. */
enum class Pace(val percent: Int, val label: Int) {
    GENTLE(5, R.string.taper_pace_gentle),
    STEADY(10, R.string.taper_pace_steady),
    QUICK(20, R.string.taper_pace_quick),
    ;

    /** At least a minute, or a small limit would never move. */
    fun stepFor(level: Int): Int = Math.round(level * percent / 100.0).toInt().coerceAtLeast(1)
}

/**
 * [allowance] lowering to [goal] by [step] a week. An existing taper is
 * re-based first, so a new step does not re-price the weeks already lived and
 * make the limit jump; see [Tapers.rebase].
 */
private fun withTaper(
    allowance: Allowance,
    goal: Int,
    step: Int,
    hard: HardTimes,
    today: LocalDate,
): Allowance {
    val base = if (allowance.taper != null) Tapers.rebase(allowance, hard, today) else allowance
    val lowest = DialScale.shortestMinutes(base.period)
    if (base.minutes <= lowest) return allowance
    return base.copy(taper = Taper(goal.coerceIn(lowest, base.minutes - 1), step, today))
}

/**
 * "Lower it gradually", closed until asked for, inside one time limit.
 *
 * Most people never want it, and it is a lot of controls to put in front of
 * them, so it says what it holds on one line and opens in place.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaperControl(
    allowance: Allowance,
    hard: HardTimes,
    today: LocalDate,
    onChange: (Allowance) -> Unit,
) {
    val res = LocalResources.current
    var open by remember { mutableStateOf(false) }
    val taper = allowance.taper
    val level = Tapers.level(allowance, hard, today)
    val lowest = DialScale.shortestMinutes(allowance.period)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    onClickLabel = stringResource(if (open) R.string.card_close else R.string.card_open),
                    role = Role.Button,
                ) { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.taper_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (taper == null) {
                        stringResource(R.string.taper_off)
                    } else {
                        stringResource(
                            R.string.taper_summary,
                            durationText(res, taper.goalMinutes),
                            res.getString(R.string.mode_allowance_week, durationText(res, taper.stepMinutes)),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (open) 180f else 0f),
            )
        }
        if (!open) return@Column
        if (taper == null) {
            Text(
                stringResource(R.string.taper_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                enabled = level > lowest,
                onClick = {
                    // Half way is a goal worth having and still one step away
                    // from being a block.
                    onChange(
                        withTaper(allowance, maxOf(lowest, level / 2), Pace.STEADY.stepFor(level), hard, today),
                    )
                },
            ) {
                Text(stringResource(R.string.taper_set_goal))
            }
            return@Column
        }

        val goal = taper.goalMinutes
        Stepper(
            label = stringResource(R.string.taper_goal_label),
            value = durationText(res, goal),
            lessDescription = stringResource(R.string.taper_goal_lower),
            moreDescription = stringResource(R.string.taper_goal_higher),
            canLess = goal > lowest,
            canMore = goal < level - 1,
            onLess = {
                val down = DialScale.stepAt(maxOf(goal - 1, 1))
                onChange(withTaper(allowance, maxOf(lowest, goal - down), taper.stepMinutes, hard, today))
            },
            onMore = {
                val up = DialScale.stepAt(goal)
                onChange(withTaper(allowance, minOf(level - 1, goal + up), taper.stepMinutes, hard, today))
            },
            endPadding = 0.dp,
        )

        // On a small limit the paces come to the same minutes, and chips that
        // all say "1 min a week" are not a choice. One chip per distinct step;
        // the words only mean something while all three are on offer.
        val steps = Pace.entries.associateBy { it.stepFor(level) }
        if (steps.size > 1) {
            Text(stringResource(R.string.taper_pace_label), style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                steps.forEach { (step, pace) ->
                    val perWeek = res.getString(R.string.mode_allowance_week, durationText(res, step))
                    FilterChip(
                        selected = step == taper.stepMinutes,
                        onClick = { onChange(withTaper(allowance, goal, step, hard, today)) },
                        label = {
                            if (steps.size == Pace.entries.size) {
                                Column {
                                    Text(stringResource(pace.label))
                                    Text(perWeek, style = MaterialTheme.typography.labelSmall)
                                }
                            } else {
                                Text(perWeek)
                            }
                        },
                    )
                }
            }
        }

        val reaches = Tapers.earliestGoalDate(allowance, hard, today)
        Text(
            if (reaches == null) {
                stringResource(R.string.taper_live_done, durationText(res, goal))
            } else {
                stringResource(
                    R.string.taper_live,
                    durationText(res, taper.stepMinutes),
                    durationText(res, goal),
                    dateText(LocalContext.current, reaches),
                )
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.taper_restart_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Stays at the level it has reached: stopping is not a step back up to
        // where it started, only a loosening of where it was headed.
        TextButton(onClick = { onChange(allowance.copy(minutes = level, taper = null)) }) {
            Text(stringResource(R.string.taper_stop))
        }
    }
}
