package cat.doorman.app.ui

import android.app.TimePickerDialog
import android.content.res.Resources
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.platform.LocalResources
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
fun limitsSummary(limits: Limits): String = limitsSummaryText(LocalResources.current, limits)

/**
 * [limitsSummary] outside a composable. Days are named whenever a rule is not
 * every day: "10 minutes an hour" on a rule that only holds on weekdays read as
 * a limit, and on a Saturday it let a video run for twenty minutes with nothing
 * on screen saying why.
 */
fun limitsSummaryText(res: Resources, limits: Limits): String = when {
    limits.blocked -> res.getString(R.string.mode_blocked)
    limits.isOff -> res.getString(R.string.mode_off)
    else -> buildList {
        limits.allowances.forEach { allowance ->
            val minutes = durationText(res, allowance.minutes)
            val text = res.getString(
                when (allowance.period) {
                    Period.HOUR -> R.string.mode_allowance_hour
                    Period.DAY -> R.string.mode_allowance_day
                    Period.WEEK -> R.string.mode_allowance_week
                },
                minutes,
            )
            add(withDays(res, text, allowance.days))
        }
        limits.windows.forEach { window ->
            add(withDays(res, "${formatMinute(window.fromMinute)}–${formatMinute(window.toMinute)}", window.days))
        }
    }.joinToString(" · ")
}

private fun withDays(res: Resources, text: String, days: Days): String =
    daysText(res, days)?.let { res.getString(R.string.mode_on_days, text, it) } ?: text

/** "weekdays", "weekend", "Mon, Wed" -- or null for every day, which goes without saying. */
fun daysText(res: Resources, days: Days): String? {
    val locale = res.configuration.locales[0]
    return when (days.mask and Days.ALL_MASK) {
        Days.ALL_MASK -> null
        Days.WEEKDAYS.mask -> res.getString(R.string.days_weekdays).lowercase(locale)
        Days.WEEKEND.mask -> res.getString(R.string.days_weekend).lowercase(locale)
        else -> DayOfWeek.entries.filter { it in days }
            .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
    }
}

/**
 * The whole of what can hold one screen or one app, in one place.
 *
 * Built around the two sentences people actually say -- "twenty minutes a day"
 * and "nothing after nine" -- rather than around a list of preset modes. Both
 * can be on at once, because that is one intention and not two, and the week
 * drawn underneath shows what the combination actually does.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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

    // Full screen, not a pop-up. A budget with its dial, a period, the days,
    // and a set of blocked hours with its own ring will never fit a dialog;
    // squeezed into one, every section was a card inside a card inside a
    // scrolling box, and Save sat at the bottom of something you could not
    // see the bottom of. Close and Save live in the bar at the top, where
    // they stay put however far down the editor goes.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // A dialog is a window of its own, drawn edge to edge here, and it
        // does not inherit the activity's status-bar icon colour: on the
        // light theme the clock and battery came out white on white.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val dark = isSystemInDarkTheme()
        SideEffect {
            window?.let {
                WindowCompat.getInsetsController(it, it.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_cancel_change),
                            )
                        }
                    },
                    actions = {
                        TextButton(onClick = { onSave(draft) }) {
                            Text(stringResource(R.string.action_save))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!help.isNullOrBlank()) {
                    Text(
                        help,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // The two absolutes first, because most people want one of
                // them and should not have to read about minutes to find out.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

                EditorHeading(stringResource(R.string.limits_usage_heading))
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
                AddButton(stringResource(R.string.limits_add_allowance)) {
                    draft = draft.copy(
                        blocked = false,
                        allowances = draft.allowances + Allowance(20, Period.DAY),
                    )
                }

                EditorHeading(stringResource(R.string.limits_hours_heading))
                Text(
                    stringResource(R.string.limits_hours_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                AddButton(stringResource(R.string.limits_add_window)) {
                    draft = draft.copy(
                        blocked = false,
                        // Nine in the evening to nine in the morning: the
                        // rule most people are reaching for, already
                        // crossing midnight so the wrap explains itself.
                        windows = draft.windows + Window(21 * 60, 9 * 60),
                    )
                }

                if (draft.windows.isNotEmpty() || draft.blocked) {
                    EditorHeading(stringResource(R.string.limits_week_heading))
                    WeekTimeline(draft)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** A section of the editor: a rule above it says where one part ends. */
@Composable
private fun EditorHeading(text: String) {
    Column(
        modifier = Modifier.padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun AddButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** The bin at the corner of a budget or a set of hours. */
@Composable
private fun RemoveButton(onRemove: () -> Unit) {
    IconButton(onClick = onRemove) {
        Icon(
            Icons.Outlined.Delete,
            contentDescription = stringResource(R.string.action_remove),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "____ minutes per ____", plus the days it applies on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllowanceRow(
    allowance: Allowance,
    onChange: (Allowance) -> Unit,
    onRemove: () -> Unit,
) {
    SurfaceCard {
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                MinutesDial(
                    minutes = allowance.minutes,
                    period = allowance.period,
                    onChange = { onChange(allowance.copy(minutes = it)) },
                )
            }
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                RemoveButton(onRemove)
            }
        }
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // One control with three parts rather than three loose chips:
            // only one period can be true, and a wrapped third chip on a line
            // of its own read as a separate setting.
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                Period.entries.forEachIndexed { index, period ->
                    SegmentedButton(
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
                        shape = SegmentedButtonDefaults.itemShape(index, Period.entries.size),
                        label = { Text(periodLabel(period), maxLines = 1) },
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
    SurfaceCard {
        Box(modifier = Modifier.fillMaxWidth()) {
            TimeRing(
                window = window,
                onChange = onChange,
                onPickExactly = onPick,
                modifier = Modifier.padding(start = 40.dp, end = 40.dp, top = 16.dp),
            )
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                RemoveButton(onRemove)
            }
        }
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (window.wrapsMidnight) {
                Text(
                    text = stringResource(R.string.limits_wraps_midnight),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DayPicker(window.days, onDaysChange)
        }
    }
}

/**
 * Every day, weekdays, weekend, or a hand-picked set.
 *
 * The seven days are one row of round toggles, the way the phone's own alarm
 * clock lays them out. As chips they wrapped onto a second line, and a week
 * broken in two after Thursday is not a shape anybody recognises.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayPicker(days: Days, onChange: (Days) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Row(modifier = Modifier.fillMaxWidth()) {
            DayOfWeek.entries.forEach { day ->
                val on = day in days
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .toggleable(
                            value = on,
                            role = Role.Checkbox,
                            onValueChange = { checked ->
                                val next = days.with(day, checked)
                                // Never leave a rule applying on no days at
                                // all: it would sit in the list looking active
                                // and do nothing.
                                if (!next.isEmpty) onChange(next)
                            },
                        )
                        .semantics {
                            contentDescription = day.getDisplayName(TextStyle.FULL, locale)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val shape = CircleShape
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .then(
                                if (on) {
                                    Modifier.background(MaterialTheme.colorScheme.primary, shape)
                                } else {
                                    Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            day.getDisplayName(TextStyle.NARROW, locale),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
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
