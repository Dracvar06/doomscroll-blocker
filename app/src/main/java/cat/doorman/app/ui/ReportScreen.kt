package cat.doorman.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
fun WeeklyReport(days: List<DayRecord>, today: LocalDate) {
    val (justGone, before) = Journal.lastTwoWeeks(days, today)
    val thisWeek = Journal.week(days, Journal.weekOf(today))

    Text(stringResource(R.string.report_heading), style = MaterialTheme.typography.headlineMedium)

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
