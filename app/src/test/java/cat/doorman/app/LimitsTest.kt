package cat.doorman.app

import cat.doorman.app.limits.Allowance
import cat.doorman.app.limits.Allowances
import cat.doorman.app.limits.Days
import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.LimitsCodec
import cat.doorman.app.limits.Period
import cat.doorman.app.limits.Window
import cat.doorman.app.limits.blockedMinutesOfWeek
import cat.doorman.app.limits.isLoosening
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Time rules, which are the part of Doorman that is easy to get subtly wrong
 * and impossible to notice: a block that quietly lifts an hour early, or covers
 * a morning it should not, looks like nothing at all until someone loses a day
 * to it.
 *
 * Midnight is the recurring hazard and it has its own section.
 */
class LimitsTest {

    private fun at(day: DayOfWeek, hour: Int, minute: Int = 0): LocalDateTime {
        // 2026-09-07 is a Monday, so day-of-week arithmetic is readable.
        val monday = LocalDateTime.of(2026, 9, 7, 0, 0)
        return monday.plusDays((day.value - 1).toLong()).withHour(hour).withMinute(minute)
    }

    private val night = Window(21 * 60, 9 * 60)

    // ---- midnight ----------------------------------------------------------

    /**
     * The case that made wrapping worth supporting. "Blocked 21:00 to 09:00" is
     * one rule, and it has to hold at 23:00 and at 02:00 and at 08:59, and let
     * go at 09:00.
     */
    @Test
    fun `a window that ends before it starts runs through the night`() {
        assertTrue(night.wrapsMidnight)
        assertTrue(night.contains(DayOfWeek.MONDAY, 21 * 60))
        assertTrue(night.contains(DayOfWeek.MONDAY, 23 * 60))
        assertTrue(night.contains(DayOfWeek.TUESDAY, 2 * 60))
        assertTrue(night.contains(DayOfWeek.TUESDAY, 8 * 60 + 59))
        assertFalse(night.contains(DayOfWeek.TUESDAY, 9 * 60))
        assertFalse(night.contains(DayOfWeek.MONDAY, 20 * 60 + 59))
    }

    /** No seam: the minute either side of midnight is held, not just one. */
    @Test
    fun `midnight itself is not a hole`() {
        val limits = Limits(windows = listOf(night))
        assertTrue(limits.blockedAt(at(DayOfWeek.MONDAY, 23, 59)))
        assertTrue(limits.blockedAt(at(DayOfWeek.TUESDAY, 0, 0)))
        assertTrue(limits.blockedAt(at(DayOfWeek.TUESDAY, 0, 1)))
    }

    @Test
    fun `a wrapping window is measured across the boundary`() {
        assertEquals(12 * 60, night.lengthMinutes)
        assertEquals(8 * 60, Window(9 * 60, 17 * 60).lengthMinutes)
    }

    /**
     * A wrapping window belongs to the day it starts on and carries into the
     * next morning whatever day that is. Any other reading makes Friday night
     * -- the last night of the working week -- the one night the rule does not
     * apply, which is the opposite of what someone setting it wants.
     */
    @Test
    fun `a weekday night runs into saturday morning`() {
        val limits = Limits(windows = listOf(night.copy(days = Days.WEEKDAYS)))
        assertTrue(limits.blockedAt(at(DayOfWeek.FRIDAY, 22, 0)))
        assertTrue(limits.blockedAt(at(DayOfWeek.SATURDAY, 3, 0)))
        // ...and does not start again on Saturday evening, which is the weekend.
        assertFalse(limits.blockedAt(at(DayOfWeek.SATURDAY, 22, 0)))
        // Monday morning belongs to Sunday night, which is not a weekday.
        assertFalse(limits.blockedAt(at(DayOfWeek.MONDAY, 3, 0)))
    }

    @Test
    fun `two windows in a day both hold`() {
        val limits = Limits(
            windows = listOf(Window(0, 9 * 60), Window(21 * 60, 23 * 60 + 59)),
        )
        assertTrue(limits.blockedAt(at(DayOfWeek.WEDNESDAY, 7, 0)))
        assertTrue(limits.blockedAt(at(DayOfWeek.WEDNESDAY, 22, 0)))
        assertFalse(limits.blockedAt(at(DayOfWeek.WEDNESDAY, 15, 0)))
    }

    // ---- budgets and schedules together ------------------------------------

    /**
     * The combination the whole redesign exists for: twenty minutes a day, and
     * nothing at all after nine. Inside the window the budget is irrelevant;
     * outside it the budget still runs.
     */
    @Test
    fun `a budget and a schedule hold at the same time`() {
        val limits = Limits(
            allowances = listOf(Allowance(20, Period.DAY)),
            windows = listOf(night),
        )
        val target = Allowances.Target("ig_feed", limits)

        val blockedByHour = Allowances.decide(listOf(target), at(DayOfWeek.MONDAY, 22))
        assertEquals(
            Allowances.Outcome.Block("ig_feed", Allowances.Reason.SCHEDULE),
            blockedByHour,
        )

        val running = Allowances.decide(listOf(target), at(DayOfWeek.MONDAY, 15))
        assertTrue(running is Allowances.Outcome.OnTheClock)
    }

    @Test
    fun `a spent budget blocks outside the scheduled hours too`() {
        val limits = Limits(
            allowances = listOf(Allowance(20, Period.DAY)),
            windows = listOf(night),
        )
        val spentToday = mapOf(
            Allowances.ledgerKey("ig_feed", Period.DAY) to
                Allowances.Spent("2026-09-07", 20 * 60_000L),
        )
        val outcome = Allowances.decide(
            listOf(Allowances.Target("ig_feed", limits, spentToday)),
            at(DayOfWeek.MONDAY, 15),
        )
        assertEquals(
            Allowances.Outcome.Block("ig_feed", Allowances.Reason.ALLOWANCE_SPENT),
            outcome,
        )
    }

    /**
     * An hourly and a daily budget on one screen are two counters. Sharing one
     * would spend both at once and the day's twenty minutes would vanish after
     * the first five.
     */
    @Test
    fun `budgets of different periods are counted separately`() {
        val limits = Limits(
            allowances = listOf(
                Allowance(5, Period.HOUR),
                Allowance(20, Period.DAY),
            ),
        )
        val outcome = Allowances.decide(
            listOf(Allowances.Target("ig_feed", limits)),
            at(DayOfWeek.MONDAY, 15),
        ) as Allowances.Outcome.OnTheClock
        assertEquals(
            listOf("ig_feed|HOUR", "ig_feed|DAY"),
            outcome.charge,
        )
        // The tighter of the two is what the countdown shows.
        assertEquals(5 * 60_000L, outcome.remainingMillis)
    }

    /** More on weekends is one of the two things weekday support is for. */
    @Test
    fun `a weekend budget replaces the weekday one`() {
        val limits = Limits(
            allowances = listOf(
                Allowance(20, Period.DAY, Days.WEEKDAYS),
                Allowance(60, Period.DAY, Days.WEEKEND),
            ),
        )
        assertEquals(20, limits.allowancesOn(DayOfWeek.WEDNESDAY).single().minutes)
        assertEquals(60, limits.allowancesOn(DayOfWeek.SUNDAY).single().minutes)
    }

    @Test
    fun `a week-long budget renews on monday`() {
        val sunday = at(DayOfWeek.SUNDAY, 12)
        val monday = at(DayOfWeek.MONDAY, 12).plusWeeks(1)
        assertTrue(
            Allowances.periodKey(Period.WEEK, sunday) !=
                Allowances.periodKey(Period.WEEK, monday),
        )
        assertEquals(
            Allowances.periodKey(Period.WEEK, at(DayOfWeek.MONDAY, 1)),
            Allowances.periodKey(Period.WEEK, at(DayOfWeek.SUNDAY, 23)),
        )
    }

    // ---- the week as drawn --------------------------------------------------

    @Test
    fun `the drawn week matches the rules it came from`() {
        val week = Limits(windows = listOf(night.copy(days = Days.WEEKDAYS)))
            .blockedMinutesOfWeek()
        fun minuteOf(day: DayOfWeek, hour: Int) = (day.value - 1) * 1440 + hour * 60
        assertTrue(week[minuteOf(DayOfWeek.MONDAY, 22)])
        assertTrue(week[minuteOf(DayOfWeek.SATURDAY, 3)])
        assertFalse(week[minuteOf(DayOfWeek.SATURDAY, 22)])
        assertFalse(week[minuteOf(DayOfWeek.WEDNESDAY, 12)])
    }

    @Test
    fun `blocked outright fills the whole week`() {
        assertTrue(Limits.BLOCKED.blockedMinutesOfWeek().all { it })
        assertTrue(Limits.OFF.blockedMinutesOfWeek().none { it })
    }

    @Test
    fun `a scheduled block reports when it lifts`() {
        val limits = Limits(windows = listOf(night))
        val until = Allowances.blockedUntil(limits, at(DayOfWeek.MONDAY, 23))
        assertEquals(at(DayOfWeek.TUESDAY, 9), until)
        assertNull(Allowances.blockedUntil(limits, at(DayOfWeek.MONDAY, 12)))
    }

    // ---- loosening ----------------------------------------------------------

    @Test
    fun `opening any minute of the week counts as loosening`() {
        val strict = Limits(windows = listOf(night))
        val looser = Limits(windows = listOf(Window(22 * 60, 9 * 60)))
        assertTrue(isLoosening(strict, looser))
        assertFalse(isLoosening(looser, strict))
    }

    @Test
    fun `raising or dropping a budget counts as loosening`() {
        val twenty = Limits(allowances = listOf(Allowance(20, Period.DAY)))
        val sixty = Limits(allowances = listOf(Allowance(60, Period.DAY)))
        assertTrue(isLoosening(twenty, sixty))
        assertFalse(isLoosening(sixty, twenty))
        assertTrue(isLoosening(twenty, Limits.OFF))
        assertFalse(isLoosening(twenty, Limits.BLOCKED))
    }

    /** Adding a schedule to a budget takes something away, so it applies now. */
    @Test
    fun `adding a schedule on top of a budget is not loosening`() {
        val budget = Limits(allowances = listOf(Allowance(20, Period.DAY)))
        val both = budget.copy(windows = listOf(night))
        assertFalse(isLoosening(budget, both))
        assertTrue(isLoosening(both, budget))
    }

    /**
     * A weekend budget bigger than the weekday one loosens the weekend without
     * touching the rest, and still has to wait.
     */
    @Test
    fun `loosening one day only still counts`() {
        val everyDay = Limits(allowances = listOf(Allowance(20, Period.DAY)))
        val generousWeekend = Limits(
            allowances = listOf(
                Allowance(20, Period.DAY, Days.WEEKDAYS),
                Allowance(60, Period.DAY, Days.WEEKEND),
            ),
        )
        assertTrue(isLoosening(everyDay, generousWeekend))
    }

    // ---- storage ------------------------------------------------------------

    @Test
    fun `limits survive being written and read back`() {
        val limits = Limits(
            allowances = listOf(
                Allowance(20, Period.DAY, Days.WEEKDAYS),
                Allowance(90, Period.WEEK),
            ),
            windows = listOf(night.copy(days = Days.WEEKDAYS), Window(13 * 60, 14 * 60)),
        )
        assertEquals(limits, LimitsCodec.decode(LimitsCodec.encode(limits)))
        assertEquals(Limits.BLOCKED, LimitsCodec.decode(LimitsCodec.encode(Limits.BLOCKED)))
        assertEquals(Limits.OFF, LimitsCodec.decode(LimitsCodec.encode(Limits.OFF)))
    }

    /**
     * Installs from before schedules existed store a single word per screen.
     * Failing to read those would silently unblock everything the user had
     * chosen, which is the worst thing this app could do to somebody.
     */
    @Test
    fun `settings from before schedules existed are still understood`() {
        assertEquals(Limits.BLOCKED, LimitsCodec.decode("blocked"))
        assertEquals(Limits.OFF, LimitsCodec.decode("off"))
        assertEquals(
            Limits(allowances = listOf(Allowance(5, Period.DAY))),
            LimitsCodec.decode("5d"),
        )
        assertEquals(
            Limits(allowances = listOf(Allowance(15, Period.HOUR))),
            LimitsCodec.decode("15h"),
        )
    }

    @Test
    fun `something unreadable is not guessed at`() {
        assertNull(LimitsCodec.decode("who knows"))
        assertNull(LimitsCodec.decode(""))
        assertNull(LimitsCodec.decode(null))
    }
}
