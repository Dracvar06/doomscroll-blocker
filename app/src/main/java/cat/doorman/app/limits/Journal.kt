package cat.doorman.app.limits

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * One day of what actually happened, as opposed to what was configured.
 *
 * Three numbers, and each was chosen because Doorman can honestly know it:
 *
 * - [stops] is how many times a block went up in front of somebody. It is the
 *   closest thing to a count of impulses, and it is the only figure available
 *   on every screen Doorman watches -- a screen that is simply blocked spends
 *   no minutes, because nobody gets to spend any.
 * - [spentMillis] is time counted against an allowance. It only exists where an
 *   allowance exists, so it is a partial picture and must never be presented as
 *   "time on your phone", which Doorman does not measure and should not claim
 *   to.
 * - [loosenings] is how many times a limit was weakened. It is the number a
 *   week later is worth asking about.
 */
@Serializable
data class DayRecord(
    val date: String,
    val stops: Int = 0,
    val spentMillis: Long = 0L,
    val loosenings: Int = 0,
    /**
     * Time in front of each held app, by package. Only apps with something
     * held in them, and never the time spent looking at a block: that is a
     * stop, and it is counted as one.
     */
    val apps: Map<String, Long> = emptyMap(),
    /**
     * The same time split by recognised screen, by rule id. What no rule
     * recognises -- a conversation, a profile -- is the app total minus these,
     * and is shown as "other" rather than guessed at.
     */
    val screens: Map<String, Long> = emptyMap(),
)

/** What a week came to, and how much of it Doorman was actually there for. */
data class WeekSummary(
    val monday: LocalDate,
    val stops: Int,
    val spentMillis: Long,
    val loosenings: Int,
    val daysRecorded: Int,
    val apps: Map<String, Long> = emptyMap(),
    val screens: Map<String, Long> = emptyMap(),
) {
    val isEmpty: Boolean get() = daysRecorded == 0
}

/**
 * The record of how the weeks went.
 *
 * Kept as one row per day rather than a running total per week, because a total
 * can only answer the question it was designed for and the rows can answer
 * questions nobody has asked yet. Ten weeks of rows is seventy small objects;
 * there is no reason to be clever.
 *
 * Pure: no clock, no storage, no Android. A report that turns over at the wrong
 * moment, or compares this week against the wrong one, is a report that lies to
 * somebody about their own habits, and that has to be testable without waiting
 * a week to find out.
 */
object Journal {

    /** How much history is worth keeping. Long enough to see a trend, short enough to forget. */
    const val KEEP_DAYS = 70L

    /** The Monday that owns a date. Weeks run Monday to Sunday, like a calendar. */
    fun weekOf(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

    /**
     * Adds to a day's row, creating it if this is the first thing that happened.
     *
     * Additive rather than replacing, because the three numbers arrive from
     * three different places at three different moments and none of them knows
     * about the others.
     */
    fun record(
        days: List<DayRecord>,
        date: LocalDate,
        stops: Int = 0,
        spentMillis: Long = 0L,
        loosenings: Int = 0,
        apps: Map<String, Long> = emptyMap(),
        screens: Map<String, Long> = emptyMap(),
    ): List<DayRecord> {
        val key = date.toString()
        val existing = days.firstOrNull { it.date == key }
            ?: DayRecord(key)
        val updated = existing.copy(
            stops = existing.stops + stops,
            spentMillis = existing.spentMillis + spentMillis,
            loosenings = existing.loosenings + loosenings,
            apps = sumByKey(existing.apps, apps),
            screens = sumByKey(existing.screens, screens),
        )
        return days.filterNot { it.date == key } + updated
    }

    /** Drops rows older than [KEEP_DAYS], and anything dated in the future. */
    fun prune(days: List<DayRecord>, today: LocalDate): List<DayRecord> {
        val oldest = today.minusDays(KEEP_DAYS)
        return days.filter { row ->
            val date = runCatching { LocalDate.parse(row.date) }.getOrNull()
            date != null && !date.isBefore(oldest) && !date.isAfter(today)
        }.sortedBy { it.date }
    }

    /** What the week beginning [monday] came to. */
    fun week(days: List<DayRecord>, monday: LocalDate): WeekSummary {
        val inWeek = days.filter { row ->
            val date = runCatching { LocalDate.parse(row.date) }.getOrNull()
            date != null && weekOf(date) == monday
        }
        return WeekSummary(
            monday = monday,
            stops = inWeek.sumOf { it.stops },
            spentMillis = inWeek.sumOf { it.spentMillis },
            loosenings = inWeek.sumOf { it.loosenings },
            daysRecorded = inWeek.size,
            apps = inWeek.fold(emptyMap()) { acc, row -> sumByKey(acc, row.apps) },
            screens = inWeek.fold(emptyMap()) { acc, row -> sumByKey(acc, row.screens) },
        )
    }

    fun sumByKey(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> =
        (a.keys + b.keys).associateWith { (a[it] ?: 0L) + (b[it] ?: 0L) }

    /**
     * The week just gone and the one before it, for comparing.
     *
     * Reported from a Monday, so the week being summarised is a finished one.
     * A report that arrives mid-week and compares three days against seven
     * would tell everybody they were doing brilliantly every Wednesday.
     */
    fun lastTwoWeeks(days: List<DayRecord>, today: LocalDate): Pair<WeekSummary, WeekSummary> {
        val lastMonday = weekOf(today).minusWeeks(1)
        return week(days, lastMonday) to week(days, lastMonday.minusWeeks(1))
    }

    /**
     * The last [count] finished weeks, oldest first, ending with the week just
     * gone.
     *
     * Two numbers can only say up or down. A run of them has a shape, and the
     * shape is the thing worth knowing -- whether a bad week was a bad week or
     * the fourth in a row. The rows to draw it have been on disk all along.
     *
     * Weeks with nothing recorded come back empty rather than being dropped, so
     * a gap stays visible as a gap instead of closing up and pretending the
     * weeks either side were consecutive.
     */
    fun recentWeeks(days: List<DayRecord>, today: LocalDate, count: Int): List<WeekSummary> {
        val lastMonday = weekOf(today).minusWeeks(1)
        return (count - 1 downTo 0).map { back ->
            week(days, lastMonday.minusWeeks(back.toLong()))
        }
    }

    /**
     * The seven days of the week beginning [monday], Monday first.
     *
     * Null for a day with no row at all, which is not the same fact as a day
     * with a row of zeroes: the first means Doorman was not watching, the
     * second means it was watching and nothing happened. Drawing them the same
     * way would credit the app for silences it never heard.
     */
    /**
     * How many finished weeks in a row end with no limit having been weakened,
     * counting back from the week just gone.
     *
     * The one streak Doorman can count honestly. It is not a claim about
     * anybody's habits -- it says nothing about how much they used their phone
     * -- but it is a true statement about sticking to a decision they made when
     * they were thinking clearly, which is the thing the app is actually for.
     *
     * A week with nothing recorded ends the streak rather than continuing it.
     * Doorman was not watching, so it has no business counting that week as
     * one it kept.
     */
    fun weeksWithoutLoosening(days: List<DayRecord>, today: LocalDate, limit: Int = 10): Int {
        val lastMonday = weekOf(today).minusWeeks(1)
        var kept = 0
        while (kept < limit) {
            val week = week(days, lastMonday.minusWeeks(kept.toLong()))
            if (week.isEmpty || week.loosenings > 0) break
            kept++
        }
        return kept
    }

    fun daysOf(days: List<DayRecord>, monday: LocalDate): List<DayRecord?> =
        (0L until 7L).map { offset ->
            val date = monday.plusDays(offset).toString()
            days.firstOrNull { it.date == date }
        }
}
