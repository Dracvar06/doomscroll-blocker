package cat.doorman.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cat.doorman.app.R
import cat.doorman.app.limits.DayRecord
import cat.doorman.app.limits.Journal
import cat.doorman.app.limits.WeekSummary
import cat.doorman.app.report.WatchedUse
import java.time.LocalDate

/**
 * How the week went.
 *
 * Two finished weeks side by side, because a number on its own says nothing: 41
 * stops is good news or bad news entirely depending on what last week was.
 *
 * It reports what Doorman can honestly know -- how often it stopped somebody,
 * how much time was counted against a budget, how many limits were weakened --
 * and nothing else. In particular it does not claim to know how long anyone
 * spent on their phone: Doorman only sees the screens it watches, and a blocked
 * screen spends no minutes at all because nobody gets to spend any.
 */
@Composable
fun WeeklyReport(
    days: List<DayRecord>,
    today: LocalDate,
    use: WatchedUse?,
    onAskForUsageAccess: () -> Unit,
) {
    val (justGone, before) = Journal.lastTwoWeeks(days, today)
    val thisWeek = Journal.week(days, Journal.weekOf(today))

    Text(stringResource(R.string.report_heading), style = MaterialTheme.typography.headlineMedium)

    PhoneUseCard(use, onAskForUsageAccess)

    if (days.isEmpty()) {
        // An empty report has to say why it is empty, or it reads as a broken
        // one. Doorman has never kept history before, so the honest answer is
        // that there has not been time yet.
        Text(
            stringResource(R.string.report_nothing_yet),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    WeekCard(stringResource(R.string.report_this_week), thisWeek, comparedTo = null)
    if (!justGone.isEmpty) {
        WeekCard(
            title = stringResource(R.string.report_last_week),
            week = justGone,
            comparedTo = before.takeUnless { it.isEmpty },
        )
    } else {
        Text(
            stringResource(R.string.report_first_week),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeekCard(title: String, week: WeekSummary, comparedTo: WeekSummary?) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Figure(stringResource(R.string.report_stops), week.stops.toString())
            Figure(
                stringResource(R.string.report_time),
                durationLabel((week.spentMillis / 60_000L).toInt()),
            )
            Figure(stringResource(R.string.report_loosenings), week.loosenings.toString())
            comparedTo?.let { Text(comparison(week, it), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun Figure(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * The sentence underneath, and the one place this screen is allowed an opinion.
 *
 * Fewer stops is stated as fewer stops, not as "well done". Doorman does not
 * know whether somebody had a good week -- they may simply have been away from
 * their phone, or have given up and switched a block off. Congratulating people
 * on a number the app cannot interpret is how a report starts lying politely.
 */
@Composable
private fun comparison(week: WeekSummary, before: WeekSummary): String = when {
    week.stops > before.stops -> (week.stops - before.stops).let {
        pluralStringResource(R.plurals.report_more_stops, it, it)
    }
    week.stops < before.stops -> (before.stops - week.stops).let {
        pluralStringResource(R.plurals.report_fewer_stops, it, it)
    }
    else -> stringResource(R.string.report_same_stops)
}

/**
 * The figure Doorman borrows rather than measures.
 *
 * Kept above the counts it makes itself, because it is the one somebody
 * actually came to find out: being stopped more often is not the same as
 * being on the phone more, and can even move the other way when a limit is
 * doing its job.
 */
@Composable
private fun PhoneUseCard(use: WatchedUse?, onAsk: () -> Unit) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.report_usage_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (use == null) {
                // The offer states the whole cost before the button, because a
                // permission this wide asked for by an app with accessibility
                // access has to be asked for plainly or not at all.
                Text(
                    stringResource(R.string.report_usage_offer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onAsk) {
                    Text(stringResource(R.string.action_usage_access))
                }
                return@Column
            }

            Figure(
                stringResource(R.string.report_last_week),
                durationLabel((use.lastWeekMillis / 60_000L).toInt()),
            )
            val before = use.weekBeforeMillis
            if (before == null) {
                // Android keeps daily figures for about a week. Saying so is
                // better than showing a zero that would read as a triumph.
                Text(
                    stringResource(R.string.report_usage_no_history),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Figure(
                    stringResource(R.string.report_usage_week_before),
                    durationLabel((before / 60_000L).toInt()),
                )
                Text(
                    usageComparison(use.lastWeekMillis - before),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun usageComparison(differenceMillis: Long): String {
    val difference = differenceMillis / 60_000L
    // Under five minutes either way is noise. Android counts a screen that was
    // left on, and calling a three-minute drift a change would teach somebody
    // to read a rounding error as progress.
    if (kotlin.math.abs(difference) < 5) return stringResource(R.string.report_usage_same)
    val amount = durationLabel(kotlin.math.abs(difference).toInt())
    return if (difference > 0) {
        stringResource(R.string.report_usage_more, amount)
    } else {
        stringResource(R.string.report_usage_less, amount)
    }
}
