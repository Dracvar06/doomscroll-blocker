package cat.doorman.app.ui

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cat.doorman.app.R
import cat.doorman.app.limits.HardTimes
import cat.doorman.app.limits.Period
import cat.doorman.app.limits.Tapers
import java.time.LocalDate

/** Most names listed in a dialog before the rest are counted. */
private const val MAX_LISTED = 3

private enum class Confirm { DAY, WEEK }

/**
 * A hard day and a hard week, for the bottom of the Right now card.
 *
 * Here and nowhere else: the block cover has no button for it, because a bad
 * moment on the cover is exactly when the answer has to stay no. Neither goes
 * through the change delay, since both have to be easy on the day they are
 * needed; each asks once instead, and says what it costs.
 */
@Composable
fun HardTimeActions(
    targets: List<HeldTarget>,
    hard: HardTimes,
    today: LocalDate,
    onTakeDay: () -> Unit,
    onTakeWeek: () -> Unit,
) {
    val res = LocalResources.current
    val context = LocalContext.current
    var confirming by remember { mutableStateOf<Confirm?>(null) }
    val limits = targets.map { it.limits }
    val allowances = limits.flatMap { it.allowances }
    val lowered = allowances.any { it.taper != null && Tapers.level(it, hard, today) < it.minutes }

    // Only daily limits that hold today: a hard day gives nothing to one that
    // does not, and would still hold the week for it.
    val daily = targets.filter { !it.limits.blocked }.mapNotNull { target ->
        val onToday = target.limits.allowancesOn(today.dayOfWeek).filter { it.period == Period.DAY }
        if (onToday.isEmpty()) null else target to onToday
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    val left = Tapers.hardDaysLeft(hard, today)
    val leftText = if (left == 0) {
        res.getString(R.string.hard_days_none_left)
    } else {
        res.getQuantityString(R.plurals.hard_days_left, left, left)
    }
    if (today in hard.hardDays) {
        StatusLine(stringResource(R.string.hard_day_on, leftText))
    } else {
        NavRow(
            icon = Icons.Outlined.Add,
            title = stringResource(R.string.hard_day_title),
            supporting = if (daily.isEmpty()) stringResource(R.string.hard_day_no_daily) else leftText,
            enabled = daily.isNotEmpty() && Tapers.canTakeHardDay(hard, today),
            onClick = { confirming = Confirm.DAY },
        )
    }

    val endsOn = Tapers.hardWeekEndsOn(hard, today)
    val nextFrom = Tapers.nextHardWeekFrom(hard, today)
    when {
        endsOn != null -> {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StatusLine(stringResource(R.string.hard_week_on, dateText(context, endsOn)))
        }
        Tapers.canTakeHardWeek(limits, hard, today) -> {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            NavRow(
                icon = Icons.Outlined.Refresh,
                title = stringResource(R.string.hard_week_title),
                supporting = stringResource(R.string.hard_week_supporting),
                onClick = { confirming = Confirm.WEEK },
            )
        }
        // Only when there is a lowering limit it would have helped: for
        // anyone else the action does not exist, and a line about when it
        // comes back would be about nothing.
        lowered && nextFrom > today -> {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StatusLine(
                stringResource(R.string.hard_week_next, dateText(context, nextFrom)),
                quiet = true,
            )
        }
    }

    when (confirming) {
        Confirm.DAY -> {
            val after = hard.copy(hardDays = hard.hardDays + today)
            val listed = daily.map { (target, dayLimits) ->
                val minutes = dayLimits.minOf { Tapers.effectiveMinutes(it, after, today) }
                res.getString(R.string.hard_item, target.name, amountText(res, Period.DAY, minutes))
            }
            val body = buildList {
                add(res.getString(R.string.hard_day_confirm_more, listNames(res, listed)))
                add(res.getString(R.string.hard_day_confirm_cost))
                if (allowances.any { it.taper != null }) add(res.getString(R.string.hard_day_confirm_taper))
                if (allowances.any { it.period != Period.DAY }) {
                    add(res.getString(R.string.hard_day_confirm_other))
                }
                add(res.getString(R.string.hard_cannot_undo))
            }
            HardDialog(
                title = stringResource(R.string.hard_day_confirm_title),
                paragraphs = body,
                confirm = stringResource(R.string.hard_day_confirm_button),
                onConfirm = {
                    confirming = null
                    onTakeDay()
                },
                onDismiss = { confirming = null },
            )
        }
        Confirm.WEEK -> {
            val after = hard.copy(hardWeeks = hard.hardWeeks + today, hardDays = hard.hardDays - today)
            val listed = targets.flatMap { target ->
                target.limits.allowances.mapNotNull { allowance ->
                    val eased = Tapers.effectiveMinutes(allowance, after, today)
                    if (eased > Tapers.level(allowance, hard, today)) {
                        res.getString(R.string.hard_item, target.name, amountText(res, allowance.period, eased))
                    } else {
                        null
                    }
                }
            }
            HardDialog(
                title = stringResource(R.string.hard_week_confirm_title),
                paragraphs = listOf(
                    res.getString(R.string.hard_week_confirm_rise, listNames(res, listed)),
                    res.getString(
                        R.string.hard_week_confirm_ends,
                        dateText(context, Tapers.hardWeekEndsOn(after, today) ?: today),
                    ),
                    res.getString(
                        R.string.hard_week_confirm_next,
                        dateText(context, Tapers.nextHardWeekFrom(after, today)),
                    ),
                    res.getString(R.string.hard_cannot_undo),
                ),
                confirm = stringResource(R.string.hard_week_confirm_button),
                onConfirm = {
                    confirming = null
                    onTakeWeek()
                },
                onDismiss = { confirming = null },
            )
        }
        null -> Unit
    }
}

/** Up to three of [items], then how many more there are. */
private fun listNames(res: Resources, items: List<String>): String {
    val shown = items.take(MAX_LISTED).joinToString(", ")
    val extra = items.size - MAX_LISTED
    return if (extra > 0) res.getString(R.string.now_blocked_more, shown, extra) else shown
}

/** A line of the card that says how things stand and offers nothing to press. */
@Composable
private fun StatusLine(text: String, quiet: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * What taking it means, in short paragraphs, before it is taken. Scrolls: in
 * Catalan at a large font the whole of it is taller than a dialog.
 */
@Composable
private fun HardDialog(
    title: String,
    paragraphs: List<String>,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_not_now)) }
        },
    )
}
