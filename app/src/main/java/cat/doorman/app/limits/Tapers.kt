package cat.doorman.app.limits

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * What an allowance is worth on a given day, once it has lowered itself and
 * once the user's hard days and hard week are counted in.
 *
 * Pure, and a function of (allowance, hard times, date) alone. Nothing here
 * stores a level or reads a clock, so setting the phone's date back or forward
 * only moves a limit along the path it was already on; it cannot leave one
 * stuck somewhere stricter than the user's own numbers.
 *
 * A taper that makes no sense (a goal not below its minutes, a step under
 * one) is read as no taper, so odd data leaves the limit at the minutes the
 * user set rather than a stricter one.
 */
object Tapers {

    /** Hard days a Monday-to-Sunday week allows. */
    const val HARD_DAYS_PER_WEEK = 2

    /** Days a hard week eases the limits for. */
    const val HARD_WEEK_DAYS = 7

    /** Days from one hard week's first day to the next one being allowed. */
    const val HARD_WEEK_SPACING_DAYS = 28

    private const val EXTRA_FLOOR = 2
    private const val EXTRA_CAP = 15

    /** Hard days and weeks are kept this long; see [HardTimes] storage. */
    const val KEEP_YEARS = 2L

    /**
     * What happened to an allowance's taper on the Monday just gone, for the
     * report. Null elsewhere: no taper, a week before the first full one, or
     * nothing left to lower.
     */
    sealed interface LastWeek {
        data class Lowered(val from: Int, val to: Int) : LastWeek

        data class Held(val by: HeldBy) : LastWeek
    }

    enum class HeldBy { HARD_DAY, HARD_WEEK }

    private fun mondayOf(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun anchorOf(taper: Taper): LocalDate =
        taper.startedOn.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))

    /**
     * The Mondays of weeks the user's hard days and hard weeks hold. A hard
     * week holds every week it overlaps, even by one day.
     */
    private fun heldWeeks(hard: HardTimes): Set<LocalDate> {
        val held = hard.hardDays.mapTo(mutableSetOf()) { mondayOf(it) }
        hard.hardWeeks.forEach { start ->
            held += mondayOf(start)
            held += mondayOf(start.plusDays(HARD_WEEK_DAYS - 1L))
        }
        return held
    }

    /**
     * The minutes the allowance is at on [today], before any hard day or hard
     * week. The starting minutes when there is no taper.
     */
    fun level(allowance: Allowance, hard: HardTimes, today: LocalDate): Int {
        val taper = allowance.taper ?: return allowance.minutes
        if (taper.goalMinutes >= allowance.minutes || taper.stepMinutes < 1) return allowance.minutes
        val anchor = anchorOf(taper)
        val monday = mondayOf(today)
        if (monday < anchor.plusDays(7)) return allowance.minutes
        // Weeks start at the anchor and the last complete one ends at this
        // Monday. Counted rather than walked, so a date far in the future costs
        // nothing.
        val complete = ChronoUnit.DAYS.between(anchor, monday) / 7
        val lastComplete = monday.minusDays(7)
        val held = heldWeeks(hard).count { it >= anchor && it <= lastComplete }
        val steps = (complete - held).coerceAtLeast(0)
        val lowered = allowance.minutes - steps * taper.stepMinutes
        return lowered.coerceAtLeast(taper.goalMinutes.toLong()).toInt()
    }

    /** The one function every decision and display of an allowance goes through. */
    fun effectiveMinutes(allowance: Allowance, hard: HardTimes, today: LocalDate): Int {
        var minutes = level(allowance, hard, today)
        val taper = allowance.taper
        if (taper != null && hardWeekEndsOn(hard, today) != null) {
            minutes = minOf(allowance.minutes.toLong(), minutes.toLong() + taper.stepMinutes).toInt()
        }
        if (allowance.period == Period.DAY && today in hard.hardDays) {
            minutes += hardDayExtra(minutes)
        }
        return minutes
    }

    /** A quarter more on a hard day, never less than two minutes or more than fifteen. */
    fun hardDayExtra(level: Int): Int =
        Math.round(level / 4.0).toInt().coerceIn(EXTRA_FLOOR, EXTRA_CAP)

    /** Hard days still to take in the week [today] falls in. */
    fun hardDaysLeft(hard: HardTimes, today: LocalDate): Int {
        val monday = mondayOf(today)
        val taken = hard.hardDays.count { mondayOf(it) == monday }
        return (HARD_DAYS_PER_WEEK - taken).coerceAtLeast(0)
    }

    fun canTakeHardDay(hard: HardTimes, today: LocalDate): Boolean =
        today !in hard.hardDays && hardDaysLeft(hard, today) > 0

    /**
     * Whether a hard week can be taken: none in the last 28 days, and some
     * taper in [limits] that has actually lowered, so there is something to step
     * back to.
     */
    fun canTakeHardWeek(limits: Collection<Limits>, hard: HardTimes, today: LocalDate): Boolean =
        today >= nextHardWeekFrom(hard, today) && limits.any { limit ->
            limit.allowances.any { it.taper != null && level(it, hard, today) < it.minutes }
        }

    /**
     * The last day of the hard week in force on [today], or null when there is
     * none. A hard week taken on a Monday ends the Sunday after.
     */
    fun hardWeekEndsOn(hard: HardTimes, today: LocalDate): LocalDate? =
        hard.hardWeeks
            .filter { today >= it && today < it.plusDays(HARD_WEEK_DAYS.toLong()) }
            .maxOrNull()
            ?.plusDays(HARD_WEEK_DAYS - 1L)

    /** The first day another hard week can be taken: [today] when nothing stands in the way. */
    fun nextHardWeekFrom(hard: HardTimes, today: LocalDate): LocalDate {
        val latest = hard.hardWeeks.maxOrNull() ?: return today
        val from = latest.plusDays(HARD_WEEK_SPACING_DAYS.toLong())
        return if (from > today) from else today
    }

    /**
     * The earliest day the allowance reaches its goal, if every week from now
     * on is a good one; null when there is no taper or it is already there.
     * Weeks that are already held are skipped.
     */
    fun earliestGoalDate(allowance: Allowance, hard: HardTimes, today: LocalDate): LocalDate? {
        val taper = allowance.taper ?: return null
        val now = level(allowance, hard, today)
        if (taper.goalMinutes >= allowance.minutes || now <= taper.goalMinutes) return null
        var remaining = (now - taper.goalMinutes + taper.stepMinutes - 1) / taper.stepMinutes
        val held = heldWeeks(hard)
        var week = maxOf(anchorOf(taper), mondayOf(today))
        while (true) {
            if (week !in held) remaining--
            if (remaining <= 0) return week.plusDays(7)
            week = week.plusDays(7)
        }
    }

    /**
     * [allowance] re-based on [today] for an edit of its goal or step: its
     * minutes become what it is worth now and the count starts over. Otherwise a
     * new step would re-price the weeks already lived and the limit would jump.
     * Pass the allowance as it was stored, before the edit; a plain limit comes
     * back unchanged.
     */
    fun rebase(allowance: Allowance, hard: HardTimes, today: LocalDate): Allowance {
        val taper = allowance.taper ?: return allowance
        return allowance.copy(
            minutes = level(allowance, hard, today),
            taper = taper.copy(startedOn = today),
        )
    }

    /**
     * How the Monday just gone treated [allowance], or null when there is
     * nothing to say. A week with both a hard day and a hard week is put down
     * to the hard day.
     */
    fun lastWeek(allowance: Allowance, hard: HardTimes, today: LocalDate): LastWeek? {
        val taper = allowance.taper ?: return null
        val week = mondayOf(today).minusDays(7)
        if (week < anchorOf(taper)) return null
        val before = level(allowance, hard, week)
        val now = level(allowance, hard, today)
        if (now < before) return LastWeek.Lowered(before, now)
        if (before <= taper.goalMinutes) return null
        if (hard.hardDays.any { mondayOf(it) == week }) return LastWeek.Held(HeldBy.HARD_DAY)
        if (week in heldWeeks(hard)) return LastWeek.Held(HeldBy.HARD_WEEK)
        return null
    }

    /**
     * Whether [to] takes a taper that [from] had and makes it easier: off, a
     * higher goal or a smaller step. Matched by period and a shared day, since
     * a limit can hold several allowances. A level that is higher today is the
     * budget comparison's to catch, not this one's.
     */
    internal fun loosensAnyTaper(from: Limits, to: Limits): Boolean {
        if (to.blocked) return false
        return from.allowances.any { old ->
            val was = old.taper ?: return@any false
            val matches = to.allowances.filter {
                it.period == old.period && it.days.mask and old.days.mask != 0
            }
            matches.isNotEmpty() && matches.none {
                val now = it.taper
                now != null && now.goalMinutes <= was.goalMinutes && now.stepMinutes >= was.stepMinutes
            }
        }
    }

    /** [hard] without anything older than [KEEP_YEARS] before [today]. */
    fun pruned(hard: HardTimes, today: LocalDate): HardTimes {
        val oldest = today.minusYears(KEEP_YEARS)
        return HardTimes(
            hardDays = hard.hardDays.filterTo(mutableSetOf()) { it >= oldest },
            hardWeeks = hard.hardWeeks.filter { it >= oldest },
        )
    }
}
