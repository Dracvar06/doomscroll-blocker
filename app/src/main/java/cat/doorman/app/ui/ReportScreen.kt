package cat.doorman.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cat.doorman.app.R
import cat.doorman.app.limits.DayRecord
import cat.doorman.app.limits.Journal
import cat.doorman.app.limits.WeekSummary
import cat.doorman.app.report.WatchedUse
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle

/**
 * How the week went.
 *
 * The report has one job that is harder than it looks: to be encouraging
 * without being dishonest. Doorman can see when it stopped somebody and when
 * they weakened a limit; it cannot see whether they had a good week. Somebody
 * stopped fewer times may have been away from their phone, or may have quietly
 * switched a block off on the Tuesday.
 *
 * So the screen is built around one rule: **a figure is coloured as better or
 * worse only where Doorman can tell which it is.**
 *
 * - Time in the held apps comes from Android and is real behaviour, so it gets
 *   a direction.
 * - Stops get a direction only in a stretch with no loosenings in it. With the
 *   configuration unchanged, fewer stops means fewer reaches for the app. With
 *   a limit weakened, the same drop can mean the opposite, and it is shown grey
 *   with the reason said out loud.
 * - Weakened limits are always worse, and the streak of weeks without one is
 *   the only thing here Doorman is willing to call a run of good weeks -- it is
 *   a statement about keeping a decision, not about anybody's habits.
 */
@Composable
fun WeeklyReport(
    days: List<DayRecord>,
    today: LocalDate,
    use: WatchedUse?,
    onAskForUsageAccess: () -> Unit,
    onReviewLimits: () -> Unit,
    coffeeNudge: Boolean = false,
    onCoffee: () -> Unit = {},
    onNotNow: () -> Unit = {},
    appTimesFor: (WeekSummary) -> List<AppTime> = { emptyList() },
) {
    val weeks = Journal.recentWeeks(days, today, count = WEEKS_CHARTED)
    val justGone = weeks.last()
    val weekBefore = weeks[weeks.lastIndex - 1]
    val thisWeek = Journal.week(days, Journal.weekOf(today))

    Text(stringResource(R.string.report_heading), style = MaterialTheme.typography.headlineMedium)

    if (days.isEmpty() && use == null) {
        // An empty report has to say why it is empty, or it reads as a broken
        // one. Doorman has never kept history before, so the honest answer is
        // that there has not been time yet.
        Text(stringResource(R.string.report_nothing_yet), style = MaterialTheme.typography.bodyMedium)
        UsageOfferCard(onAskForUsageAccess)
        return
    }

    HeadlineCard(use, justGone, weekBefore, onAskForUsageAccess)
    StreakCard(Journal.weeksWithoutLoosening(days, today))
    if (justGone.loosenings > 0) LooseningCard(justGone.loosenings, onReviewLimits)
    ThisWeekCard(Journal.daysOf(days, thisWeek.monday), thisWeek, today)
    AppTimeCard(stringResource(R.string.report_app_time_this_week), appTimesFor(thisWeek))
    TrendCard(weeks)
    DayCard(Journal.daysOf(days, justGone.monday), justGone)
    AppTimeCard(stringResource(R.string.report_app_time_last_week), appTimesFor(justGone))
    if (coffeeNudge) SupportNudgeCard(onCoffee, onNotNow)
}

/**
 * Last on the page, once a season, only for people with a month of use behind
 * them. Below the figures rather than above: somebody came here to see their
 * week, and the ask waits until they have.
 */
@Composable
private fun SupportNudgeCard(onCoffee: () -> Unit, onNotNow: () -> Unit) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.support_nudge_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.support_nudge_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCoffee) { Text(stringResource(R.string.action_coffee)) }
                TextButton(onClick = onNotNow) { Text(stringResource(R.string.action_not_now)) }
            }
        }
    }
}

/** Eight bars is two months: long enough for a shape, short enough to read. */
private const val WEEKS_CHARTED = 8

// Fixed rather than taken from the theme. Material's dynamic colour is derived
// from the wallpaper, so "the good one" could come out anywhere on the wheel,
// and a report where better and worse are two shades of the same purple is a
// report nobody can skim. Matched to the dials, which use the same two ends.
private val BETTER = Color(0xFF63BE72)
private val WORSE = Color(0xFFDF6350)

private enum class Tone { BETTER, WORSE, UNKNOWABLE }

// ---------------------------------------------------------------- headline

/**
 * The one figure somebody came to find out, at the size of a headline.
 *
 * Time in the held apps when Android will say, because that is the question
 * ("was I on my phone less?") and Doorman genuinely cannot answer it alone.
 * Where it will not, the number of stops stands in -- clearly labelled as a
 * different thing, not as a substitute.
 */
@Composable
private fun HeadlineCard(
    use: WatchedUse?,
    justGone: WeekSummary,
    weekBefore: WeekSummary,
    onAskForUsageAccess: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.report_last_week),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (use != null) {
                Headline(durationLabel((use.lastWeekMillis / 60_000L).toInt()))
                Text(
                    stringResource(R.string.report_usage_title),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // A week total is hard to feel; a day is not. Android's figure
                // covers the whole week whether or not Doorman was watching,
                // so dividing by seven is honest here in a way it would not be
                // for Doorman's own counts.
                Text(
                    stringResource(
                        R.string.report_per_day,
                        durationLabel((use.lastWeekMillis / 7 / 60_000L).toInt()),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val before = use.weekBeforeMillis
                if (before == null) {
                    Note(stringResource(R.string.report_usage_no_history))
                } else {
                    val minutes = (use.lastWeekMillis - before) / 60_000L
                    // Less time is better, so the sign is flipped against the
                    // stops chart below, where the direction is the same but
                    // the meaning is not.
                    DeltaRow(
                        difference = minutes.toInt(),
                        tone = if (minutes <= 0) Tone.BETTER else Tone.WORSE,
                        formatted = durationLabel(kotlin.math.abs(minutes).toInt()),
                        deadband = QUIET_MINUTES,
                    )
                }
            } else {
                Headline(justGone.stops.toString())
                Text(
                    stringResource(R.string.report_stops),
                    style = MaterialTheme.typography.bodyMedium,
                )
                StopsDelta(justGone, weekBefore)
            }

            PartialWeekNote(justGone)
            if (use == null) {
                Note(stringResource(R.string.report_usage_offer))
                TextButton(
                    onClick = onAskForUsageAccess,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(stringResource(R.string.action_usage_access))
                }
            }
        }
    }
}

/**
 * Under five minutes either way is noise: Android counts a screen left face-up
 * on a table, and calling a three-minute drift a change teaches somebody to
 * read a rounding error as progress.
 */
private const val QUIET_MINUTES = 5

/** @see QUIET_MINUTES */
private const val QUIET_STOPS = 2

@Composable
private fun Headline(value: String) {
    Text(
        value,
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * Stops, with a direction only where the configuration held still.
 *
 * This is the whole honesty argument in one function. Fewer stops in a week
 * where a limit was weakened is not an improvement and may be the exact
 * opposite, so the drop is shown without a colour and the reason is printed
 * underneath rather than left for somebody to work out.
 */
@Composable
private fun StopsDelta(justGone: WeekSummary, weekBefore: WeekSummary) {
    if (weekBefore.isEmpty) {
        Note(stringResource(R.string.report_first_week))
        return
    }
    val difference = justGone.stops - weekBefore.stops
    val steady = justGone.loosenings == 0 && weekBefore.loosenings == 0
    DeltaRow(
        difference = difference,
        tone = when {
            !steady -> Tone.UNKNOWABLE
            difference <= 0 -> Tone.BETTER
            else -> Tone.WORSE
        },
        formatted = kotlin.math.abs(difference).toString(),
        // One stop either way across a whole week is not news. Reporting it as
        // a direction would put an arrow on the screen every single week and
        // teach somebody to stop reading the arrow.
        deadband = QUIET_STOPS,
    )
    if (!steady) Note(stringResource(R.string.report_stops_unknowable))
}

/**
 * An arrow, an amount, and a colour only when the colour would be true.
 *
 * [deadband] is how small a change has to be before it is called no change.
 */
@Composable
private fun DeltaRow(difference: Int, tone: Tone, formatted: String, deadband: Int) {
    if (kotlin.math.abs(difference) < deadband) {
        Note(stringResource(R.string.report_delta_same))
        return
    }
    val colour = when (tone) {
        Tone.BETTER -> BETTER
        Tone.WORSE -> WORSE
        Tone.UNKNOWABLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(colour.copy(alpha = 0.16f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                stringResource(
                    if (difference < 0) R.string.report_delta_down else R.string.report_delta_up,
                    formatted,
                ),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colour,
            )
        }
        Text(
            stringResource(R.string.report_delta_against),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------------ streak

/**
 * Weeks in a row without a limit being weakened.
 *
 * The only thing on this screen Doorman will call a run of good weeks, and it
 * is careful about what it means: not "you used your phone less" but "you kept
 * the decision you made when you were thinking clearly". That is a claim the
 * app can actually stand behind.
 */
@Composable
private fun StreakCard(weeks: Int) {
    if (weeks == 0) return
    Card(
        colors = CardDefaults.cardColors(containerColor = BETTER.copy(alpha = 0.14f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                pluralStringResource(R.plurals.report_streak, weeks, weeks),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.report_streak_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The question the user asked for: you loosened these, did you mean to keep them? */
@Composable
private fun LooseningCard(loosenings: Int, onReviewLimits: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = WORSE.copy(alpha = 0.14f))) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                pluralStringResource(R.plurals.report_loosened, loosenings, loosenings),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.report_loosened_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onReviewLimits) {
                Text(stringResource(R.string.action_review_limits))
            }
        }
    }
}

// ------------------------------------------------------------------ charts

@Composable
private fun TrendCard(weeks: List<WeekSummary>) {
    if (weeks.count { !it.isEmpty } < 2) return
    val gaps = weeks.any { it.isEmpty }
    ChartCard(
        title = stringResource(R.string.report_trend_title),
        caption = stringResource(
            if (gaps) R.string.report_trend_caption_gap else R.string.report_trend_caption,
        ),
        bars = weeks.map { Bar(label = "", value = it.stops, recorded = !it.isEmpty) },
    )
}

@Composable
private fun DayCard(days: List<DayRecord?>, week: WeekSummary) {
    if (week.isEmpty) return
    ChartCard(
        title = stringResource(R.string.report_days_title),
        caption = stringResource(R.string.report_days_caption),
        bars = dayBars(days),
    )
}

@Composable
private fun dayBars(days: List<DayRecord?>, today: LocalDate? = null, monday: LocalDate? = null): List<Bar> {
    val locale = LocalResources.current.configuration.locales[0]
    return days.mapIndexed { index, day ->
        val date = monday?.plusDays(index.toLong())
        Bar(
            label = DayOfWeek.of(index + 1).getDisplayName(TextStyle.SHORT, locale).take(2),
            value = day?.stops ?: 0,
            recorded = day != null,
            emphasis = date != null && date == today,
            future = date != null && today != null && date.isAfter(today),
        )
    }
}

private class Bar(
    val label: String,
    val value: Int,
    val recorded: Boolean,
    /** Today: drawn stronger, because it is the one still being written. */
    val emphasis: Boolean = false,
    /** A day that has not happened yet: drawn as nothing, not as a gap. */
    val future: Boolean = false,
)

@Composable
private fun ChartCard(title: String, caption: String, bars: List<Bar>) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                // The peak is only worth printing when the bars themselves
                // carry no numbers.
                if (bars.size > 7) {
                    Text(
                        stringResource(R.string.report_chart_peak, bars.maxOf { it.value }),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            BarChart(bars, showValues = bars.size <= 7)
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Bars, in layout rather than on a canvas.
 *
 * A Row of weighted columns gets rounded corners, theme colours and right-to-
 * left layouts for nothing, and there is no axis worth the drawing code: the
 * peak is printed above the chart and the shape is the point.
 *
 * A week Doorman did not record is drawn as a flat grey stub, never as a bar of
 * height zero -- an unwatched week and a quiet week look nothing alike and must
 * not read alike.
 */
@Composable
private fun BarChart(bars: List<Bar>, showValues: Boolean = false, height: Dp = 84.dp) {
    val peak = bars.maxOfOrNull { it.value }?.coerceAtLeast(1) ?: 1
    // Read aloud as a list of values: a chart an accessibility service cannot
    // describe would be a poor joke from an app built on one.
    val spoken = bars.filter { !it.future }.joinToString { bar ->
        if (bar.recorded) "${bar.label} ${bar.value}" else "${bar.label} –"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Headroom for the values, which sit above the tallest bar.
            .padding(top = if (showValues) VALUE_LIFT else 0.dp)
            .semantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        bars.forEach { bar ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(height),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (showValues && bar.recorded && bar.value > 0) {
                        Text(
                            bar.value.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(bottom = 2.dp)
                                .offset(y = height * (1f - bar.value.toFloat() / peak) - VALUE_LIFT),
                        )
                    }
                    if (!bar.future) Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Full height, faint: a week Doorman did not watch
                            // has to read as a hole in the run. Drawn as a stub
                            // it was indistinguishable from a flawless week,
                            // which is the one lie this chart must not tell.
                            .height(
                                if (bar.recorded) {
                                    (height * (bar.value.toFloat() / peak)).coerceAtLeast(STUB)
                                } else {
                                    height
                                },
                            )
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(
                                when {
                                    !bar.recorded ->
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.07f)
                                    bar.emphasis -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                },
                            ),
                    )
                }
                if (bar.label.isNotEmpty()) {
                    Text(
                        bar.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val STUB = 3.dp

/** How far above its bar a value sits. */
private val VALUE_LIFT = 16.dp

// -------------------------------------------------------------- this week

/**
 * Last, and deliberately plain.
 *
 * A week in progress cannot be compared with a finished one -- three days
 * against seven would tell everybody they were doing brilliantly every
 * Wednesday -- so it is shown as a running total and nothing is concluded
 * from it.
 */
@Composable
private fun ThisWeekCard(days: List<DayRecord?>, week: WeekSummary, today: LocalDate) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.report_this_week),
                style = MaterialTheme.typography.titleMedium,
            )
            // The week being lived, day by day, with today drawn stronger.
            // For somebody in their first week this is the whole report, and
            // it should look like one rather than like a footnote.
            if (!week.isEmpty) {
                BarChart(dayBars(days, today, week.monday), showValues = true)
                Text(
                    stringResource(R.string.report_this_week_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Figure(stringResource(R.string.report_stops), week.stops.toString())
            Figure(
                stringResource(R.string.report_time),
                durationLabel((week.spentMillis / 60_000L).toInt()),
            )
            Figure(stringResource(R.string.report_loosenings), week.loosenings.toString())
        }
    }
}

@Composable
private fun UsageOfferCard(onAsk: () -> Unit) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.report_usage_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Note(stringResource(R.string.report_usage_offer))
            TextButton(onClick = onAsk) {
                Text(stringResource(R.string.action_usage_access))
            }
        }
    }
}

// ------------------------------------------------------------------ pieces

/**
 * Said out loud when Doorman was only there for part of a week.
 *
 * Without it, a week the service spent switched off looks like a week of
 * heroic restraint, and the report would be congratulating somebody for its
 * own absence.
 */
@Composable
private fun PartialWeekNote(week: WeekSummary) {
    if (week.isEmpty || week.daysRecorded >= 7) return
    Note(pluralStringResource(R.plurals.report_partial_week, week.daysRecorded, week.daysRecorded))
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
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

// ------------------------------------------------------------- by app

/**
 * Time in one held app, with the part of it Doorman could name.
 *
 * Built outside this file, because naming needs the rules and the string
 * table; the card only needs labels and numbers.
 */
data class AppTime(
    val label: String,
    val millis: Long,
    /** Recognised screens inside the app, largest first. */
    val screens: List<Pair<String, Long>>,
)

/**
 * The screen-time card, with the one thing a screen-time app cannot do: say
 * which part of the app the time went to. "Four hours in Instagram" is what
 * the phone already says. "Three of them in Stories, with Reels blocked" is
 * what changes what somebody does next.
 *
 * Only time Doorman was there for, and never time spent looking at a block;
 * the caption says so, because a figure that quietly differs from the
 * phone's own is a figure that gets called a bug.
 */
@Composable
private fun AppTimeCard(title: String, times: List<AppTime>) {
    if (times.isEmpty()) return
    val most = times.maxOf { it.millis }.coerceAtLeast(1L)
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            times.forEach { app ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            durationLabel((app.millis / 60_000L).toInt()),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    // A bar scaled to the longest app, so the eye ranks them
                    // before the numbers are read.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(app.millis.toFloat() / most)
                                .height(6.dp)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    val other = app.millis - app.screens.sumOf { it.second }
                    val otherLabel = stringResource(R.string.report_other_screens)
                    val res = LocalResources.current
                    val parts = app.screens +
                        (if (other >= 60_000L) listOf(otherLabel to other) else emptyList())
                    if (parts.isNotEmpty()) {
                        Text(
                            parts.joinToString(" · ") { (label, ms) ->
                                "$label ${durationText(res, (ms / 60_000L).toInt())}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.report_app_time_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
