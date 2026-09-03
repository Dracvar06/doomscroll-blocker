package cat.doorman.app.limits

import java.time.DayOfWeek
import java.time.LocalDateTime

/** Which days of the week a limit applies on, held as a bitmask over Mon..Sun. */
@JvmInline
value class Days(val mask: Int) {

    operator fun contains(day: DayOfWeek): Boolean = mask and (1 shl (day.value - 1)) != 0

    fun with(day: DayOfWeek, on: Boolean): Days {
        val bit = 1 shl (day.value - 1)
        return Days(if (on) mask or bit else mask and bit.inv())
    }

    val isEmpty: Boolean get() = mask and ALL_MASK == 0

    companion object {
        const val ALL_MASK = 0b1111111
        val EVERY_DAY = Days(ALL_MASK)
        val WEEKDAYS = Days(0b0011111)
        val WEEKEND = Days(0b1100000)
    }
}

/** Minutes of use permitted in each [period], on the days in [days]. */
data class Allowance(
    val minutes: Int,
    val period: Period,
    val days: Days = Days.EVERY_DAY,
)

/**
 * A stretch of the day during which something is held.
 *
 * Times are minutes from midnight, 0..1439. [toMinute] not being after
 * [fromMinute] means the window runs past midnight: 21:00 to 09:00 is one
 * window covering the night, not two windows meeting at a seam.
 *
 * That choice is the whole reason midnight is not a special case here. Someone
 * blocking their evenings and mornings writes one rule and reads one rule back.
 * The alternative -- forbidding the wrap and making them enter 21:00-23:59 plus
 * 00:00-09:00 -- leaves a minute open at midnight, asks whether the day ends at
 * 23:59 or 24:00, and shows two rules for one intention.
 *
 * For a window that wraps, [days] means the days it *ends* on -- the mornings.
 * A night is blocked because of the day it leads into, not the day it left:
 * people close their evenings to get up for work. So "blocked 21:00 to 09:00,
 * Monday to Friday" covers Sunday night through Thursday night, leaves Friday
 * and Saturday nights alone, and closes again on Sunday evening.
 *
 * Reading it the other way -- as the day the window starts on -- gets Friday
 * night exactly backwards: it blocks the one evening with no morning to protect
 * and frees the one that has school or work waiting.
 *
 * For a window that does not wrap, both readings are the same day and the
 * question does not arise.
 */
data class Window(
    val fromMinute: Int,
    val toMinute: Int,
    val days: Days = Days.EVERY_DAY,
) {
    val wrapsMidnight: Boolean get() = toMinute <= fromMinute

    /** How long it lasts, in minutes, wrap included. */
    val lengthMinutes: Int
        get() = if (wrapsMidnight) MINUTES_PER_DAY - fromMinute + toMinute else toMinute - fromMinute

    fun contains(day: DayOfWeek, minuteOfDay: Int): Boolean = if (wrapsMidnight) {
        // The evening belongs to tomorrow's morning; the small hours belong to
        // today's.
        (day.plus(1) in days && minuteOfDay >= fromMinute) ||
            (day in days && minuteOfDay < toMinute)
    } else {
        day in days && minuteOfDay >= fromMinute && minuteOfDay < toMinute
    }
}

/**
 * Everything holding one screen or one app.
 *
 * The three kinds stack rather than replacing each other, which is the point:
 * "twenty minutes a day, and nothing at all after nine" is one sentence a
 * person says and two rules the app has to keep at once. A single mode could
 * only ever express one of them.
 */
data class Limits(
    /** Held always, regardless of time or minutes spent. */
    val blocked: Boolean = false,
    val allowances: List<Allowance> = emptyList(),
    val windows: List<Window> = emptyList(),
) {
    val isOff: Boolean get() = !blocked && allowances.isEmpty() && windows.isEmpty()

    /** The allowances in force on [day]; the others belong to other days. */
    fun allowancesOn(day: DayOfWeek): List<Allowance> = allowances.filter { day in it.days }

    /** The window holding this at [day] and [minuteOfDay], if any. */
    fun windowAt(day: DayOfWeek, minuteOfDay: Int): Window? =
        windows.firstOrNull { it.contains(day, minuteOfDay) }

    fun blockedAt(at: LocalDateTime): Boolean =
        blocked || windowAt(at.dayOfWeek, at.hour * 60 + at.minute) != null

    companion object {
        val OFF = Limits()
        val BLOCKED = Limits(blocked = true)
    }
}

const val MINUTES_PER_DAY = 24 * 60
const val MINUTES_PER_WEEK = 7 * MINUTES_PER_DAY

/**
 * Every minute of a week, marked with whether it is held.
 *
 * Ten thousand booleans rather than clever interval arithmetic, and
 * deliberately so. Comparing two sets of limits to see which is stricter, and
 * drawing the week for the user to look at, are both exact and obvious on a
 * flat array, and both become fiddly the moment windows wrap midnight, overlap
 * each other, or apply on different days. The array is built in well under a
 * millisecond and it is impossible to get subtly wrong.
 *
 * Index 0 is Monday 00:00.
 */
fun Limits.blockedMinutesOfWeek(): BooleanArray {
    val week = BooleanArray(MINUTES_PER_WEEK)
    if (blocked) {
        week.fill(true)
        return week
    }
    DayOfWeek.entries.forEach { day ->
        val dayStart = (day.value - 1) * MINUTES_PER_DAY
        for (minute in 0 until MINUTES_PER_DAY) {
            if (windowAt(day, minute) != null) week[dayStart + minute] = true
        }
    }
    return week
}

/**
 * True when [to] permits anything [from] did not.
 *
 * This is what the change delay hangs on, and with three kinds of limit at once
 * it is no longer a single number to compare. A change counts as loosening if
 * it opens any minute of the week that used to be held, or raises any
 * allowance, or drops one -- and only a change that does none of those is
 * treated as tightening and applied at once.
 *
 * Erring towards "this is loosening" is the safe direction: the cost of a wait
 * on a change that did not need one is a wait, and the cost of skipping it is
 * the impulse the whole mechanism exists to slow down.
 */
fun isLoosening(from: Limits, to: Limits): Boolean {
    val before = from.blockedMinutesOfWeek()
    val after = to.blockedMinutesOfWeek()
    for (i in before.indices) if (before[i] && !after[i]) return true

    // Every day has to keep at least as tight a budget as it had.
    DayOfWeek.entries.forEach { day ->
        if (dailyBudget(to, day, after) > dailyBudget(from, day, before)) return true
    }
    return false
}

/**
 * The minutes a day permits, for comparing two sets of limits.
 *
 * A day with no allowance is unbudgeted, which is looser than any number --
 * unless every minute of it is held anyway, in which case it permits nothing.
 * Without that second case, replacing "twenty minutes a day" with "blocked
 * always" reads as a loosening, because the stricter setting has no allowance
 * to compare, and the change would sit through a wait it does not deserve.
 */
private fun dailyBudget(limits: Limits, day: DayOfWeek, week: BooleanArray): Int {
    val start = (day.value - 1) * MINUTES_PER_DAY
    var open = false
    for (minute in 0 until MINUTES_PER_DAY) {
        if (!week[start + minute]) {
            open = true
            break
        }
    }
    if (!open) return 0
    return limits.allowancesOn(day).minOfOrNull { it.dailyMinutes } ?: Int.MAX_VALUE
}

/**
 * What an allowance permits in a day, used only to compare two of them.
 *
 * Not a forecast of use: five minutes an hour is a looser rein than five
 * minutes a day and has to sort that way, and a weekly budget has to sit on the
 * same scale as both.
 */
val Allowance.dailyMinutes: Int
    get() = when (period) {
        Period.HOUR -> minutes * 24
        Period.DAY -> minutes
        Period.WEEK -> maxOf(1, minutes / 7)
    }
