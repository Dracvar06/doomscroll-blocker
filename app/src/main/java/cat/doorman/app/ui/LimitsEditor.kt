package cat.doorman.app.ui

import android.app.TimePickerDialog
import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
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
import cat.doorman.app.gray.GrayPolicy
import cat.doorman.app.gray.GraySetting
import cat.doorman.app.limits.Allowance
import cat.doorman.app.limits.Days
import cat.doorman.app.limits.DialScale
import cat.doorman.app.limits.HardTimes
import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.Period
import cat.doorman.app.limits.ReelQuota
import cat.doorman.app.limits.Tapers
import cat.doorman.app.limits.Window
import cat.doorman.app.limits.isLoosening
import java.time.DayOfWeek
import java.time.LocalDate
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
fun limitsSummary(limits: Limits): String =
    limitsSummaryText(LocalResources.current, limits, LocalHardTimes.current)

/**
 * [limitsSummary] outside a composable. Days are named whenever a rule is not
 * every day: "10 minutes an hour" on a rule that only holds on weekdays read as
 * a limit, and on a Saturday it let a video run for twenty minutes with nothing
 * on screen saying why.
 *
 * Minutes are what the limit is worth on [today]. [withRelief] says whether a
 * hard day or hard week counts: the list says what holds right now, while the
 * editor and the change dialog speak of the limit itself, and a quarter more
 * showing up in a number being typed would read as a mistake.
 */
fun limitsSummaryText(
    res: Resources,
    limits: Limits,
    hard: HardTimes,
    today: LocalDate = LocalDate.now(),
    withRelief: Boolean = true,
): String = when {
    limits.blocked -> res.getString(R.string.mode_blocked)
    limits.isOff -> res.getString(R.string.mode_off)
    else -> (
        limits.allowances.map { allowanceText(res, it, hard, today, withRelief) } +
            limits.windows.map { windowText(res, it) } +
            listOfNotNull(limits.reels?.let { reelsText(res, it) })
        ).joinToString(" · ")
}

/**
 * [limitsSummaryText] as sentences, for the top of the editor: "Blocked
 * 21:00–09:00. Otherwise 20 minutes a day." Built from whole strings with the
 * parts in them, because the order of the clauses is not the same in every
 * language and gluing English fragments together would read badly in the others.
 */
fun limitsSentenceText(
    res: Resources,
    limits: Limits,
    hard: HardTimes,
    today: LocalDate = LocalDate.now(),
): String {
    if (limits.blocked) return res.getString(R.string.limits_sentence_blocked)
    if (limits.isOff) return res.getString(R.string.limits_sentence_allowed)
    val hours = limits.windows.joinToString(", ") { windowText(res, it) }
    val limit = (
        limits.allowances.map { allowanceText(res, it, hard, today, withRelief = false) } +
            listOfNotNull(limits.reels?.let { reelsText(res, it) })
        ).joinToString(", ")
    return when {
        hours.isEmpty() -> res.getString(R.string.limits_sentence_limit, limit)
        limit.isEmpty() -> res.getString(R.string.limits_sentence_limit, hours)
        else -> res.getString(R.string.limits_sentence_join, hours, limit)
    }
}

private fun allowanceText(
    res: Resources,
    allowance: Allowance,
    hard: HardTimes,
    today: LocalDate,
    withRelief: Boolean,
): String {
    val level = Tapers.level(allowance, hard, today)
    val minutes = if (withRelief) Tapers.effectiveMinutes(allowance, hard, today) else level
    var text = amountText(res, allowance.period, minutes)
    // Only while there is still lowering to do: "lowering to 20" on a limit
    // that is already at 20 says something that is no longer going on.
    val taper = allowance.taper
    if (taper != null && level > taper.goalMinutes) {
        text = res.getString(R.string.mode_taper, text, durationText(res, taper.goalMinutes))
    }
    return withDays(res, text, allowance.days)
}

/** "20 minutes a day": [minutes] said over [period]. */
internal fun amountText(res: Resources, period: Period, minutes: Int): String = res.getString(
    when (period) {
        Period.HOUR -> R.string.mode_allowance_hour
        Period.DAY -> R.string.mode_allowance_day
        Period.WEEK -> R.string.mode_allowance_week
    },
    durationText(res, minutes),
)

// Says "Blocked", not just the times: hours alone read as when something is
// open, and a row of them has to be told apart from an allowance without
// relying on a colour.
private fun windowText(res: Resources, window: Window): String {
    val hours = "${formatMinute(window.fromMinute)}–${formatMinute(window.toMinute)}"
    return withDays(res, res.getString(R.string.mode_blocked_hours, hours), window.days)
}

private fun reelsText(res: Resources, quota: ReelQuota): String = res.getQuantityString(
    R.plurals.mode_reels,
    quota.reels,
    quota.reels,
    durationText(res, quota.minutes),
)

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
 *
 * Allowed, Limited or Blocked first, then a sentence saying what the draft
 * amounts to, then -- only for Limited -- one collapsed section per kind of
 * limit. A section is where a further control for that kind of limit goes: a
 * new one for the time limit belongs inside [TimeLimitSection], above or below
 * the dials, and needs nothing else in the editor to change.
 *
 * Grey has a section of its own after the limits and is saved with them, as one
 * choice. The two are kept apart in the model, and are one question on the
 * screen: how this should treat me.
 */
@Composable
fun LimitsEditor(
    title: String,
    limits: Limits,
    help: String?,
    suggestions: List<Window> = emptyList(),
    /** Whether this is a screen of single videos, where a reel quota makes sense. */
    reelsAllowed: Boolean = false,
    /**
     * False for a screen where minutes make no sense, such as the stories row
     * on a home feed: only Allowed or Blocked is offered.
     */
    supportsAllowance: Boolean = true,
    /** Grey as it is now, and whether this target can be made grey at all. */
    gray: GraySetting? = null,
    supportsGrey: Boolean = false,
    grayCanWrite: Boolean = false,
    /** For telling the user, before they save, that the change will wait. */
    changeDelaySeconds: Int = 0,
    onDismiss: () -> Unit,
    onSave: (Limits, GraySetting?) -> Unit,
) {
    val res = LocalResources.current
    var draft by remember { mutableStateOf(limits) }
    var grey by remember { mutableStateOf(gray) }
    // The limits as they were when the user left Limited, so a slip onto Allowed
    // and back does not cost them the dials they had set.
    var limitedStash by remember { mutableStateOf<Limits?>(null) }

    val kind = LimitKind.of(draft)
    val changed = draft != limits || (supportsGrey && grey != gray)
    // Said before Save rather than found out after: the dialog that follows is
    // a surprise to anyone who did not know loosening waits.
    val hard = LocalHardTimes.current
    val today = LocalDate.now()
    val waits = changed && changeDelaySeconds > 0 &&
        (isLoosening(limits, draft, today, hard) || (supportsGrey && GrayPolicy.isLoosening(gray, grey)))

    EditorDialog(
        title = title,
        changed = changed,
        onDismiss = onDismiss,
        onSave = { onSave(draft, grey) },
    ) {
        if (!help.isNullOrBlank()) HelpText(help)

        ChoiceRow(
            options = buildList {
                add(LimitKind.ALLOWED to stringResource(R.string.mode_off))
                if (supportsAllowance) add(LimitKind.LIMITED to stringResource(R.string.limits_seg_limited))
                add(LimitKind.BLOCKED to stringResource(R.string.mode_blocked))
            },
            selected = kind,
            onSelect = { chosen ->
                if (kind == LimitKind.LIMITED) limitedStash = draft
                draft = when (chosen) {
                    LimitKind.ALLOWED -> Limits.OFF
                    LimitKind.BLOCKED -> Limits.BLOCKED
                    // Never Limited with nothing in it: that would save as
                    // Allowed.
                    LimitKind.LIMITED ->
                        limitedStash ?: Limits(allowances = listOf(DEFAULT_ALLOWANCE))
                }
            },
        )

        SurfaceCard(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(limitsSentenceText(res, draft, hard, today), style = MaterialTheme.typography.titleMedium)
                if (supportsGrey && grey != null) {
                    Text(
                        graySummaryText(res, grey),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (waits) {
                    Text(
                        stringResource(R.string.limits_applies_in, changeDelaySeconds),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (kind == LimitKind.LIMITED) {
            val none = stringResource(R.string.limits_section_none)
            EditorSection(
                title = stringResource(R.string.limits_usage_heading),
                summary = if (draft.allowances.isEmpty()) {
                    none
                } else {
                    limitsSummaryText(res, Limits(allowances = draft.allowances), hard, today, withRelief = false)
                },
            ) {
                TimeLimitSection(draft, hard, today) { draft = it }
            }
            EditorSection(
                title = stringResource(R.string.limits_hours_heading),
                summary = if (draft.windows.isEmpty()) {
                    none
                } else {
                    limitsSummaryText(res, Limits(windows = draft.windows), hard)
                },
            ) {
                WindowsEditor(
                    windows = draft.windows,
                    heading = null,
                    hint = stringResource(R.string.limits_hours_hint),
                    addLabel = stringResource(R.string.limits_add_window),
                    suggestions = suggestions,
                    onChange = { draft = draft.copy(windows = it) },
                )
            }
            if (reelsAllowed) {
                EditorSection(
                    title = stringResource(R.string.limits_reels_heading),
                    summary = draft.reels?.let { limitsSummaryText(res, Limits(reels = it), hard) } ?: none,
                ) {
                    ReelsSection(draft) { draft = it }
                }
            }
        }

        // Right under the limits and closed, with what it says in its header:
        // at the foot of a long editor nobody found it, and grey needs no block,
        // so it is here for an Allowed screen too.
        if (supportsGrey) {
            EditorSection(
                title = stringResource(R.string.colour_heading),
                summary = graySummaryText(res, grey),
            ) {
                ColourControls(
                    grey = grey,
                    canWrite = grayCanWrite,
                    suggestions = suggestions,
                    onChange = { grey = it },
                )
            }
        }

        if (kind != LimitKind.ALLOWED) {
            EditorHeading(stringResource(R.string.limits_week_heading))
            WeekTimeline(draft)
        }
        Spacer(Modifier.height(24.dp))
    }
}

private val DEFAULT_ALLOWANCE = Allowance(20, Period.DAY)

/**
 * Nine in the evening to nine in the morning: the rule most people are
 * reaching for, already crossing midnight so the wrap explains itself.
 */
internal val DEFAULT_WINDOW = Window(21 * 60, 9 * 60)

/** The three things a draft can amount to, as the control at the top has it. */
private enum class LimitKind {
    ALLOWED, LIMITED, BLOCKED;

    companion object {
        fun of(limits: Limits): LimitKind = when {
            limits.blocked -> BLOCKED
            limits.isOff -> ALLOWED
            else -> LIMITED
        }
    }
}

/**
 * One control with the choices as parts of it, rather than loose chips: only
 * one can be true, and a wrapped last chip read as a separate setting.
 *
 * No tick in the selected part. Three words side by side are as much as a
 * narrow screen at a large font in Catalan has room for, and the tick was the
 * thing that made "Bloquejat" run out of it. Whether a part is chosen is still
 * said to a screen reader, and shown by its fill.
 */
@Composable
internal fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
                contentPadding = PaddingValues(horizontal = 4.dp),
                label = { Text(label, maxLines = 2, textAlign = TextAlign.Center) },
            )
        }
    }
}

/**
 * The help for what is being edited, cut to two lines until asked for.
 *
 * Some of it runs to a paragraph, and all of it sat above the control that
 * people came to use.
 */
@Composable
private fun HelpText(text: String) {
    var expanded by remember { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
    Column {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            // Only measured while cut: once open the text no longer overflows,
            // and the button has to stay to close it again.
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.help_less else R.string.help_more))
            }
        }
    }
}

/**
 * A part of the editor that says what it holds on one line and opens in place.
 * Closed, the editor shows the whole of what is set at a glance; opened, it is
 * the same dial or ring as ever.
 */
@Composable
private fun EditorSection(
    title: String,
    summary: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(
                    onClickLabel = stringResource(if (open) R.string.card_close else R.string.card_open),
                    role = Role.Button,
                ) { open = !open }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    summary,
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
        if (open) {
            Column(
                modifier = Modifier.padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/** Minutes of use: a few common amounts one tap away, then the dials. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeLimitSection(
    draft: Limits,
    hard: HardTimes,
    today: LocalDate,
    onDraft: (Limits) -> Unit,
) {
    val res = LocalResources.current
    val first = draft.allowances.firstOrNull()
    // "Twenty minutes a day" is what most people are after, and it should not
    // take a turn of a dial to say it.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(10, 20, 60).forEach { minutes ->
            FilterChip(
                selected = first?.period == Period.DAY && Tapers.level(first, hard, today) == minutes,
                onClick = {
                    val chosen = first?.copy(period = Period.DAY)?.restartedAt(minutes, today)
                        ?: Allowance(minutes, Period.DAY)
                    onDraft(draft.copy(allowances = listOf(chosen) + draft.allowances.drop(1)))
                },
                label = {
                    Text(res.getString(R.string.mode_allowance_day, durationText(res, minutes)))
                },
            )
        }
    }
    draft.allowances.forEachIndexed { index, allowance ->
        AllowanceRow(
            allowance = allowance,
            hard = hard,
            today = today,
            onChange = { updated ->
                onDraft(
                    draft.copy(
                        allowances = draft.allowances.toMutableList().also { it[index] = updated },
                    ),
                )
            },
            onRemove = {
                onDraft(draft.copy(allowances = draft.allowances.filterIndexed { i, _ -> i != index }))
            },
        )
    }
    AddButton(stringResource(R.string.limits_add_allowance)) {
        onDraft(draft.copy(allowances = draft.allowances + DEFAULT_ALLOWANCE))
    }
}

@Composable
private fun ReelsSection(draft: Limits, onDraft: (Limits) -> Unit) {
    Text(
        stringResource(R.string.limits_reels_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val quota = draft.reels
    if (quota == null) {
        AddButton(stringResource(R.string.limits_add_reels)) {
            onDraft(draft.copy(reels = ReelQuota.DEFAULT))
        }
    } else {
        ReelQuotaRow(
            quota = quota,
            onChange = { onDraft(draft.copy(reels = it)) },
            onRemove = { onDraft(draft.copy(reels = null)) },
        )
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
    hard: HardTimes,
    today: LocalDate,
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
                    // What the limit is at now, not where it started: the dial
                    // edits the limit as it stands.
                    minutes = Tapers.level(allowance, hard, today),
                    period = allowance.period,
                    onChange = { onChange(allowance.restartedAt(it, today)) },
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
                                allowance.copy(period = period).restartedAt(
                                    DialScale.clampTo(Tapers.level(allowance, hard, today), period),
                                    today,
                                ),
                            )
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, Period.entries.size),
                        label = { Text(periodLabel(period), maxLines = 1) },
                    )
                }
            }
            DayPicker(allowance.days) { onChange(allowance.copy(days = it)) }
            TaperControl(allowance, hard, today, onChange)
        }
    }
}

/** The stretches a reel quota can be set over, in minutes, shortest first. */
private val REEL_STRETCHES = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240)

private const val MAX_REELS = 50

/**
 * "__ reels every __": two steppers rather than a dial. A reel count and a
 * handful of stretches are small numbers a person steps through, and a dial
 * would make "one reel every fifteen minutes" a matter of aim.
 */
@Composable
private fun ReelQuotaRow(
    quota: ReelQuota,
    onChange: (ReelQuota) -> Unit,
    onRemove: () -> Unit,
) {
    val res = LocalResources.current
    // The nearest stretch to whatever is stored, so a value from outside the
    // list still has somewhere to step from.
    val stretchIndex = REEL_STRETCHES.indices.minByOrNull {
        kotlin.math.abs(REEL_STRETCHES[it] - quota.minutes)
    } ?: 0
    SurfaceCard {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Stepper(
                    label = stringResource(R.string.limits_reels_label),
                    value = quota.reels.toString(),
                    lessDescription = stringResource(R.string.limits_reels_fewer),
                    moreDescription = stringResource(R.string.limits_reels_more),
                    canLess = quota.reels > 1,
                    canMore = quota.reels < MAX_REELS,
                    onLess = { onChange(quota.copy(reels = quota.reels - 1)) },
                    onMore = { onChange(quota.copy(reels = quota.reels + 1)) },
                )
                Stepper(
                    label = stringResource(R.string.limits_reels_every_label),
                    value = durationText(res, REEL_STRETCHES[stretchIndex]),
                    lessDescription = stringResource(R.string.limits_reels_shorter),
                    moreDescription = stringResource(R.string.limits_reels_longer),
                    canLess = stretchIndex > 0,
                    canMore = stretchIndex < REEL_STRETCHES.lastIndex,
                    onLess = { onChange(quota.copy(minutes = REEL_STRETCHES[stretchIndex - 1])) },
                    onMore = { onChange(quota.copy(minutes = REEL_STRETCHES[stretchIndex + 1])) },
                )
            }
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                RemoveButton(onRemove)
            }
        }
    }
}

@Composable
internal fun Stepper(
    label: String,
    value: String,
    lessDescription: String,
    moreDescription: String,
    canLess: Boolean,
    canMore: Boolean,
    onLess: () -> Unit,
    onMore: () -> Unit,
    /** Room for a control laid over the corner, such as the bin. */
    endPadding: androidx.compose.ui.unit.Dp = 48.dp,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = endPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onLess,
            enabled = canLess,
            modifier = Modifier.semantics { contentDescription = lessDescription },
        ) {
            Text("−", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.widthIn(min = 72.dp),
            textAlign = TextAlign.Center,
        )
        IconButton(
            onClick = onMore,
            enabled = canMore,
            modifier = Modifier.semantics { contentDescription = moreDescription },
        ) {
            Text("+", style = MaterialTheme.typography.titleLarge)
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


/**
 * The full-screen shell every editor sits in: Close at the top, Save and
 * Discard in a bar along the bottom, the content scrolling in between.
 *
 * Full screen, not a pop-up. A budget with its dial, a period, the days, and a
 * set of blocked hours with its own ring will never fit a dialog; squeezed into
 * one, every section was a card inside a card inside a scrolling box. The bar
 * stays put however far down the editor goes, and Save is a button, not a word
 * in a corner, because it is the one thing everybody here came to press.
 *
 * Leaving with something changed asks first. Back and the outside of the window
 * are gestures people make without deciding anything, and a draft of dials and
 * hours is a lot to lose to one of them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorDialog(
    title: String,
    changed: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    val leave = { if (changed) confirmDiscard = true else onDismiss() }
    Dialog(
        onDismissRequest = leave,
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
                        IconButton(onClick = leave) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_cancel_change),
                            )
                        }
                    },
                )
            },
            bottomBar = {
                BottomAppBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = leave) {
                        Text(stringResource(R.string.action_discard))
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onSave, enabled = changed) {
                        Text(stringResource(R.string.action_save))
                    }
                    Spacer(Modifier.width(8.dp))
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.limits_discard_title)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.limits_discard_keep))
                }
            },
        )
    }
}

/**
 * A list of hours with its clock faces, the hours already used elsewhere one tap
 * away, and a button to add more. Shared by the limits and by grayscale, which
 * both speak of the hours of a week in exactly the same way.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WindowsEditor(
    windows: List<Window>,
    /** Null when the section it sits in already has a title. */
    heading: String?,
    hint: String,
    addLabel: String,
    suggestions: List<Window>,
    onChange: (List<Window>) -> Unit,
) {
    val context = LocalContext.current
    if (heading != null) EditorHeading(heading)
    Text(
        hint,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    windows.forEachIndexed { index, window ->
        WindowRow(
            window = window,
            onChange = { updated ->
                onChange(windows.toMutableList().also { it[index] = updated })
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
                        onChange(windows.toMutableList().also { it[index] = updated })
                    },
                    current / 60,
                    current % 60,
                    true,
                ).show()
            },
            onDaysChange = { days ->
                onChange(windows.toMutableList().also { it[index] = window.copy(days = days) })
            },
            onRemove = { onChange(windows.filterIndexed { i, _ -> i != index }) },
        )
    }
    // Whatever hours the user has already set somewhere else, one tap away.
    // Someone closing five apps overnight was picking the same two times out of
    // a clock face five times over, which is the sort of tedium that ends with
    // three of them half done.
    val reusable = suggestions.filter { it !in windows }
    if (reusable.isNotEmpty()) {
        Text(
            stringResource(R.string.limits_reuse),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            reusable.forEach { window ->
                AssistChip(
                    onClick = { onChange(windows + window) },
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
    AddButton(addLabel) { onChange(windows + DEFAULT_WINDOW) }
}
