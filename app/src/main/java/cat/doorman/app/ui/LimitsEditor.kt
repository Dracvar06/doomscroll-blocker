package cat.doorman.app.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cat.doorman.app.R
import cat.doorman.app.limits.Allowance
import cat.doorman.app.limits.Days
import cat.doorman.app.limits.DialScale
import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.Period
import cat.doorman.app.limits.Window
import java.time.DayOfWeek
import java.time.format.TextStyle

/** Minutes from midnight as "21:00". */
fun formatMinute(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

/**
 * A one-line summary of what is holding something, for the list.
 *
 * Says the strictest true thing first. Someone scanning a list of twenty rows
 * is asking "is this held, and roughly how", not reading a specification.
 */
@Composable
fun limitsSummary(limits: Limits): String = when {
    limits.blocked -> stringResource(R.string.mode_blocked)
    limits.isOff -> stringResource(R.string.mode_off)
    else -> buildList {
        limits.allowances.forEach { allowance ->
            val minutes = durationLabel(allowance.minutes)
            add(
                when (allowance.period) {
                    Period.HOUR -> stringResource(R.string.mode_allowance_hour, minutes)
                    Period.DAY -> stringResource(R.string.mode_allowance_day, minutes)
                    Period.WEEK -> stringResource(R.string.mode_allowance_week, minutes)
                },
            )
        }
        limits.windows.forEach { window ->
            add("${formatMinute(window.fromMinute)}–${formatMinute(window.toMinute)}")
        }
    }.joinToString(" · ")
}

/**
 * The whole of what can hold one screen or one app, in one place.
 *
 * Built around the two sentences people actually say -- "twenty minutes a day"
 * and "nothing after nine" -- rather than around a list of preset modes. Both
 * can be on at once, because that is one intention and not two, and the week
 * drawn underneath shows what the combination actually does.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LimitsEditor(
    title: String,
    limits: Limits,
    help: String?,
    suggestions: List<Window> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (Limits) -> Unit,
) {
    var draft by remember { mutableStateOf(limits) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!help.isNullOrBlank()) {
                    Text(help, style = MaterialTheme.typography.bodySmall)
                }

                // The two absolutes first, because most people want one of them
                // and should not have to read about minutes to find out.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.isOff,
                        onClick = { draft = Limits.OFF },
                        label = { Text(stringResource(R.string.mode_off)) },
                    )
                    FilterChip(
                        selected = draft.blocked,
                        onClick = { draft = Limits.BLOCKED },
                        label = { Text(stringResource(R.string.mode_blocked)) },
                    )
                }

                HorizontalDivider()

                Text(
                    stringResource(R.string.limits_usage_heading),
                    style = MaterialTheme.typography.titleSmall,
                )
                draft.allowances.forEachIndexed { index, allowance ->
                    AllowanceRow(
                        allowance = allowance,
                        onChange = { updated ->
                            draft = draft.copy(
                                blocked = false,
                                allowances = draft.allowances.toMutableList()
                                    .also { it[index] = updated },
                            )
                        },
                        onRemove = {
                            draft = draft.copy(
                                allowances = draft.allowances.filterIndexed { i, _ -> i != index },
                            )
                        },
                    )
                }
                AssistChip(
                    onClick = {
                        draft = draft.copy(
                            blocked = false,
                            allowances = draft.allowances + Allowance(20, Period.DAY),
                        )
                    },
                    label = { Text(stringResource(R.string.limits_add_allowance)) },
                )

                HorizontalDivider()

                Text(
                    stringResource(R.string.limits_hours_heading),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.limits_hours_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                draft.windows.forEachIndexed { index, window ->
                    WindowRow(
                        window = window,
                        onChange = { updated ->
                            draft = draft.copy(
                                blocked = false,
                                windows = draft.windows.toMutableList()
                                    .also { it[index] = updated },
                            )
                        },
                        onPick = { isStart ->
                            val current = if (isStart) window.fromMinute else window.toMinute
                            TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    val value = hour * 60 + minute
                                    val updated = if (isStart) {
                                        window.copy(fromMinute = value)
                                    } else {
                                        window.copy(toMinute = value)
                                    }
                                    draft = draft.copy(
                                        blocked = false,
                                        windows = draft.windows.toMutableList()
                                            .also { it[index] = updated },
                                    )
                                },
                                current / 60,
                                current % 60,
                                true,
                            ).show()
                        },
                        onDaysChange = { days ->
                            draft = draft.copy(
                                windows = draft.windows.toMutableList()
                                    .also { it[index] = window.copy(days = days) },
                            )
                        },
                        onRemove = {
                            draft = draft.copy(
                                windows = draft.windows.filterIndexed { i, _ -> i != index },
                            )
                        },
                    )
                }
                // Whatever hours the user has already set somewhere else, one
                // tap away. Someone closing five apps overnight was picking the
                // same two times out of a clock face five times over, which is
                // the sort of tedium that ends with three of them half done.
                val reusable = suggestions.filter { it !in draft.windows }
                if (reusable.isNotEmpty()) {
                    Text(
                        stringResource(R.string.limits_reuse),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        reusable.forEach { window ->
                            AssistChip(
                                onClick = {
                                    draft = draft.copy(
                                        blocked = false,
                                        windows = draft.windows + window,
                                    )
                                },
                                label = {
                                    Text(
                                        "${formatMinute(window.fromMinute)}\u2013" +
                                            formatMinute(window.toMinute),
                                    )
                                },
                            )
                        }
                    }
                }
                AssistChip(
                    onClick = {
                        draft = draft.copy(
                            blocked = false,
                            // Nine in the evening to nine in the morning: the
                            // rule most people are reaching for, already
                            // crossing midnight so the wrap explains itself.
                            windows = draft.windows + Window(21 * 60, 9 * 60),
                        )
                    },
                    label = { Text(stringResource(R.string.limits_add_window)) },
                )

                if (draft.windows.isNotEmpty() || draft.blocked) {
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.limits_week_heading),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    WeekTimeline(draft)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel_change))
            }
        },
    )
}

/** "____ minutes per ____", plus the days it applies on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllowanceRow(
    allowance: Allowance,
    onChange: (Allowance) -> Unit,
    onRemove: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onRemove) {
                    Text(stringResource(R.string.action_remove))
                }
            }
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                MinutesDial(
                    minutes = allowance.minutes,
                    period = allowance.period,
                    onChange = { onChange(allowance.copy(minutes = it)) },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Period.entries.forEach { period ->
                    FilterChip(
                        selected = period == allowance.period,
                        onClick = {
                            // The dial's reach changes with the period, so the
                            // budget has to come with it. Three hours a day
                            // switched to "per hour" would otherwise leave the
                            // handle off the end of its own dial.
                            onChange(
                                allowance.copy(
                                    period = period,
                                    minutes = DialScale.clampTo(allowance.minutes, period),
                                ),
                            )
                        },
                        label = { Text(periodLabel(period)) },
                    )
                }
            }
            DayPicker(allowance.days) { onChange(allowance.copy(days = it)) }
        }
    }
}

/** "Blocked from ____ to ____", plus the days it starts on. */
@Composable
private fun WindowRow(
    window: Window,
    onChange: (Window) -> Unit,
    onPick: (Boolean) -> Unit,
    onDaysChange: (Days) -> Unit,
    onRemove: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRemove) {
                    Text(stringResource(R.string.action_remove))
                }
            }
            TimeRing(
                window = window,
                onChange = onChange,
                onPickExactly = onPick,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            if (window.wrapsMidnight) {
                Text(
                    text = stringResource(R.string.limits_wraps_midnight),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            DayPicker(window.days, onDaysChange)
        }
    }
}

/** Every day, weekdays, weekend, or a hand-picked set. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayPicker(days: Days, onChange: (Days) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                Days.EVERY_DAY to R.string.days_every,
                Days.WEEKDAYS to R.string.days_weekdays,
                Days.WEEKEND to R.string.days_weekend,
            ).forEach { (preset, label) ->
                FilterChip(
                    selected = days.mask == preset.mask,
                    onClick = { onChange(preset) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DayOfWeek.entries.forEach { day ->
                FilterChip(
                    selected = day in days,
                    onClick = {
                        val next = days.with(day, day !in days)
                        // Never leave a rule applying on no days at all: it
                        // would sit in the list looking active and do nothing.
                        if (!next.isEmpty) onChange(next)
                    },
                    label = {
                        Text(day.getDisplayName(TextStyle.NARROW, locale))
                    },
                )
            }
        }
    }
}

@Composable
private fun periodLabel(period: Period): String = stringResource(
    when (period) {
        Period.HOUR -> R.string.period_hour
        Period.DAY -> R.string.period_day
        Period.WEEK -> R.string.period_week
    },
)
