package cat.doorman.app.report

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * When the weekly report is due.
 *
 * Monday morning, because that is the first moment a week can be spoken about
 * in the past tense. A report that arrived on Sunday evening would be reporting
 * on a week still in progress, and one that arrived at a random hour would be
 * an interruption rather than a summary.
 *
 * Nine o'clock rather than midnight: this is a thing to read, not an alarm, and
 * a notification that lands while somebody is asleep is a notification they
 * meet as a stale badge instead of as news.
 *
 * Kept free of Android and of the clock so the arithmetic can be tested.
 */
object WeeklySchedule {

    val HOUR: LocalTime = LocalTime.of(9, 0)

    /**
     * The next Monday at [HOUR] strictly after [now].
     *
     * Strictly: firing again at the instant a report was just delivered would
     * deliver it twice, and the receiver reschedules itself the moment it runs.
     */
    fun nextFireAt(now: LocalDateTime): LocalDateTime {
        val thisWeek = now.toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atTime(HOUR)
        if (thisWeek.isAfter(now)) return thisWeek
        return now.toLocalDate()
            .with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            .atTime(HOUR)
    }
}
