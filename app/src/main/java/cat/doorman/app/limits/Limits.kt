package cat.doorman.app.limits

import java.time.DayOfWeek
import java.time.LocalDate
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

/**
 * Minutes of use permitted in each [period], on the days in [days].
 *
 * [minutes] is the level the allowance started at and stays that way: a
 * [taper] lowers what it is worth from week to week without rewriting it. What
 * it is worth on a given day is [Tapers.effectiveMinutes], and that is what
 * anything deciding or displaying a limit has to ask.
 */
data class Allowance(
    val minutes: Int,
    val period: Period,
    val days: Days = Days.EVERY_DAY,
    val taper: Taper? = null,
)

/**
 * A limit that lowers itself by [stepMinutes] after each full week without a
 * hard day, until it reaches [goalMinutes]. Counted from [startedOn]; see
 * [Tapers] for exactly which weeks count.
 */
data class Taper(
    val goalMinutes: Int,
    val stepMinutes: Int,
    val startedOn: LocalDate,
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
    /**
     * How many reels may be scrolled through in a stretch of minutes. Only
     * meaningful on a screen the rules mark as counting reels; anywhere else it
     * is never offered and never read.
     */
    val reels: ReelQuota? = null,
    /**
     * Only meaningful on an app-level limit. Null means every screen of the
     * app; a set means the limit applies, and is charged, only while one of
     * those screens is in front. Anywhere else -- messages, a screen nobody
     * recognised, no screen known at all -- the app is left alone: this fails
     * open. An empty set therefore applies nowhere.
     */
    val onlyOn: Set<String>? = null,
    /**
     * Only meaningful on an app-level limit: screens the limit leaves alone,
     * however the rest of the app is held. This is the shape a plan gives an
     * app with messages in it -- every minute in Instagram counts except the
     * ones spent in a conversation, and whatever part the user chose to free.
     *
     * Counting all the rest, screens nobody recognised included, is what
     * keeps time from leaking out through a screen the rules never named. But
     * covering an unrecognised screen is another matter: it could be a
     * conversation the rules failed to read, and a blocked conversation is the
     * one failure this app exists to avoid. So under an exception list an
     * unrecognised screen is counted and never covered ([coversOn]).
     */
    val exceptOn: Set<String>? = null,
) {
    val isOff: Boolean get() = !blocked && allowances.isEmpty() && windows.isEmpty() && reels == null

    /**
     * Whether an app-level limit is in force, and charged, with [screenId] in
     * front. A screen nobody could name (null) is outside any [onlyOn] set,
     * and inside the rest of the app under [exceptOn].
     */
    fun appliesOn(screenId: String?): Boolean {
        if (onlyOn != null && (screenId == null || screenId !in onlyOn)) return false
        if (exceptOn != null && screenId != null && screenId in exceptOn) return false
        return true
    }

    /**
     * Whether an app-level limit that has run out may cover [screenId]. The
     * same as [appliesOn], except that with an exception list a screen nobody
     * could name is left uncovered: see [exceptOn].
     */
    fun coversOn(screenId: String?): Boolean =
        appliesOn(screenId) && !(exceptOn != null && screenId == null)

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
 * A taper is judged on its path as well as its level today: taking it off,
 * raising its goal or shrinking its step changes nothing today and still
 * loosens everything after it. Adding one, or tightening it, is applied at once.
 *
 * [today] and [hard] only matter to tapers. The default for [today] reads the
 * clock, for callers that have no date to hand.
 *
 * Erring towards "this is loosening" is the safe direction: the cost of a wait
 * on a change that did not need one is a wait, and the cost of skipping it is
 * the impulse the whole mechanism exists to slow down.
 */
fun isLoosening(
    from: Limits,
    to: Limits,
    today: LocalDate = LocalDate.now(),
    hard: HardTimes = HardTimes.EMPTY,
): Boolean {
    if (Tapers.loosensAnyTaper(from, to)) return true
    // Budgets are compared at today's level, not at the starting minutes: a
    // taper that has already lowered is held to what it is worth now.
    val before = from.blockedMinutesOfWeek()
    val after = to.blockedMinutesOfWeek()
    for (i in before.indices) if (before[i] && !after[i]) return true

    // Letting more reels through, or none counted at all, is loosening too --
    // unless the screen is held outright, in which case no quota matters.
    if (!to.blocked && ReelQuotas.isLooser(from.reels, to.reels)) return true

    // Null covers every screen, so a narrower scope lets more through: null to
    // a set, or a set that has lost a screen. The other way round is tighter.
    if (!from.isOff && scopeNarrowed(from.onlyOn, to.onlyOn)) return true
    // An exception list works the other way round: every screen added to it is
    // one more the limit no longer reaches.
    if (!from.isOff && exceptionsWidened(from.exceptOn, to.exceptOn)) return true

    // Every day has to keep at least as tight a budget as it had.
    val nowFrom = from.atLevel(hard, today)
    val nowTo = to.atLevel(hard, today)
    DayOfWeek.entries.forEach { day ->
        if (dailyBudget(nowTo, day, after) > dailyBudget(nowFrom, day, before)) return true
    }
    return false
}

private fun scopeNarrowed(from: Set<String>?, to: Set<String>?): Boolean = when {
    to == null -> false
    from == null -> true
    else -> !to.containsAll(from)
}

/** Null means no exceptions, so any set at all frees something, as does a larger one. */
private fun exceptionsWidened(from: Set<String>?, to: Set<String>?): Boolean = when {
    to == null -> false
    from == null -> to.isNotEmpty()
    else -> !from.containsAll(to)
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

/** These limits with every allowance worth what it is on [today], for comparing. */
private fun Limits.atLevel(hard: HardTimes, today: LocalDate): Limits =
    copy(allowances = allowances.map { it.copy(minutes = Tapers.level(it, hard, today), taper = null) })
