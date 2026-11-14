package cat.doorman.app

import cat.doorman.app.limits.Allowance
import cat.doorman.app.limits.Allowances
import cat.doorman.app.limits.Days
import cat.doorman.app.limits.HardTimes
import cat.doorman.app.limits.HardTimesCodec
import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.LimitsCodec
import cat.doorman.app.limits.Period
import cat.doorman.app.limits.Taper
import cat.doorman.app.limits.Tapers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import cat.doorman.app.limits.isLoosening
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Limits that lower themselves, and the hard days and hard week that hold them
 * back. Everything is a function of a date, so the dates are written out.
 */
class TaperTest {

    // A Monday.
    private val monday = LocalDate.of(2026, 1, 5)
    private val none = HardTimes.EMPTY

    private fun tapered(
        minutes: Int = 60,
        goal: Int = 20,
        step: Int = 5,
        startedOn: LocalDate = monday,
        period: Period = Period.DAY,
    ) = Allowance(minutes, period, taper = Taper(goal, step, startedOn))

    private fun level(a: Allowance, day: LocalDate, hard: HardTimes = none) = Tapers.level(a, hard, day)

    // ---- level --------------------------------------------------------------

    @Test
    fun `a limit holds its starting level for the first full week`() {
        val a = tapered()
        assertEquals(60, level(a, monday.minusDays(3)))
        assertEquals(60, level(a, monday))
        assertEquals(60, level(a, monday.plusDays(6)))
    }

    @Test
    fun `it lowers at each Monday and only then`() {
        val a = tapered()
        assertEquals(55, level(a, monday.plusDays(7)))
        assertEquals(55, level(a, monday.plusDays(13)))
        assertEquals(50, level(a, monday.plusDays(14)))
        assertEquals(40, level(a, monday.plusWeeks(4)))
    }

    @Test
    fun `it stops at the goal and stays there`() {
        val a = tapered()
        assertEquals(20, level(a, monday.plusWeeks(8)))
        assertEquals(20, level(a, monday.plusWeeks(9)))
        assertEquals(20, level(a, monday.plusYears(40)))
        // A step that does not divide evenly still lands on the goal.
        assertEquals(20, level(tapered(minutes = 60, goal = 20, step = 30), monday.plusWeeks(2)))
    }

    @Test
    fun `starting mid-week the first step comes seven days after the next Monday`() {
        val a = tapered(startedOn = monday.plusDays(2))
        val anchor = monday.plusWeeks(1)
        assertEquals(60, level(a, anchor))
        assertEquals(60, level(a, anchor.plusDays(6)))
        assertEquals(55, level(a, anchor.plusWeeks(1)))
    }

    @Test
    fun `no taper is the starting minutes`() {
        assertEquals(60, level(Allowance(60, Period.DAY), monday.plusYears(1)))
    }

    @Test
    fun `a clock set back never goes below the starting level`() {
        assertEquals(60, level(tapered(), monday.minusYears(5)))
    }

    // ---- hard days ----------------------------------------------------------

    @Test
    fun `a hard day holds exactly its own week`() {
        val a = tapered()
        val hard = HardTimes(hardDays = setOf(monday.plusDays(3)))
        assertEquals(60, level(a, monday.plusWeeks(1), hard))
        // Only the one week was held: the next still lowers.
        assertEquals(55, level(a, monday.plusWeeks(2), hard))
        assertEquals(45, level(a, monday.plusWeeks(3), none))
    }

    @Test
    fun `two hard days in one week hold it once`() {
        val a = tapered()
        val hard = HardTimes(hardDays = setOf(monday.plusDays(1), monday.plusDays(5)))
        assertEquals(55, level(a, monday.plusWeeks(2), hard))
    }

    @Test
    fun `a hard day before the anchor holds nothing`() {
        val a = tapered(startedOn = monday.plusDays(2))
        val hard = HardTimes(hardDays = setOf(monday.plusDays(3)))
        assertEquals(55, level(a, monday.plusWeeks(2), hard))
    }

    // ---- hard week ----------------------------------------------------------

    @Test
    fun `a hard week across two calendar weeks holds both`() {
        val a = tapered()
        // Taken on a Thursday: it overlaps that week and the next.
        val start = monday.plusDays(3)
        val hard = HardTimes(hardWeeks = listOf(start))
        assertEquals(60, level(a, monday.plusWeeks(1), hard))
        assertEquals(60, level(a, monday.plusWeeks(2), hard))
        assertEquals(55, level(a, monday.plusWeeks(3), hard))
    }

    @Test
    fun `a hard week taken on a Monday holds only that week`() {
        val a = tapered()
        val hard = HardTimes(hardWeeks = listOf(monday.plusWeeks(1)))
        assertEquals(55, level(a, monday.plusWeeks(1), hard))
        assertEquals(55, level(a, monday.plusWeeks(2), hard))
        assertEquals(50, level(a, monday.plusWeeks(3), hard))
    }

    @Test
    fun `a hard week eases by one step for exactly seven days`() {
        val a = tapered()
        val start = monday.plusWeeks(3).plusDays(2)
        val hard = HardTimes(hardWeeks = listOf(start))
        fun eff(day: LocalDate) = Tapers.effectiveMinutes(a, hard, day)
        assertEquals(45, eff(start.minusDays(1)))
        assertEquals(50, eff(start))
        assertEquals(50, eff(start.plusDays(6)))
        assertEquals(45, eff(start.plusDays(7)))
        assertEquals(start.plusDays(6), Tapers.hardWeekEndsOn(hard, start))
        assertEquals(start.plusDays(6), Tapers.hardWeekEndsOn(hard, start.plusDays(6)))
        assertNull(Tapers.hardWeekEndsOn(hard, start.plusDays(7)))
        assertNull(Tapers.hardWeekEndsOn(hard, start.minusDays(1)))
    }

    @Test
    fun `a hard week never lifts a limit above its starting minutes`() {
        val a = tapered(minutes = 60, goal = 50, step = 30)
        val hard = HardTimes(hardWeeks = listOf(monday.plusWeeks(3)))
        assertEquals(60, Tapers.effectiveMinutes(a, hard, monday.plusWeeks(3)))
    }

    @Test
    fun `a hard week leaves a limit with no taper alone`() {
        val plain = Allowance(30, Period.DAY)
        val hard = HardTimes(hardWeeks = listOf(monday))
        assertEquals(30, Tapers.effectiveMinutes(plain, hard, monday))
    }

    // ---- hard day extra -----------------------------------------------------

    @Test
    fun `a hard day adds a quarter, never under two or over fifteen minutes`() {
        assertEquals(2, Tapers.hardDayExtra(5))
        assertEquals(2, Tapers.hardDayExtra(8))
        assertEquals(5, Tapers.hardDayExtra(20))
        assertEquals(10, Tapers.hardDayExtra(40))
        assertEquals(15, Tapers.hardDayExtra(60))
        assertEquals(15, Tapers.hardDayExtra(600))
    }

    @Test
    fun `the extra is on daily limits, on that date only`() {
        val hard = HardTimes(hardDays = setOf(monday))
        val daily = Allowance(40, Period.DAY)
        assertEquals(50, Tapers.effectiveMinutes(daily, hard, monday))
        assertEquals(40, Tapers.effectiveMinutes(daily, hard, monday.plusDays(1)))
        assertEquals(40, Tapers.effectiveMinutes(Allowance(40, Period.HOUR), hard, monday))
        assertEquals(40, Tapers.effectiveMinutes(Allowance(40, Period.WEEK), hard, monday))
    }

    @Test
    fun `the extra is a quarter of the level the taper is at`() {
        val a = tapered()
        val hard = HardTimes(hardDays = setOf(monday.plusWeeks(4).plusDays(1)))
        // Level 40 that week, so ten more.
        assertEquals(50, Tapers.effectiveMinutes(a, hard, monday.plusWeeks(4).plusDays(1)))
    }

    // ---- taking them --------------------------------------------------------

    @Test
    fun `a third hard day in a week is refused and the next week allows one`() {
        val wednesday = monday.plusDays(2)
        var hard = HardTimes(hardDays = setOf(monday))
        assertEquals(1, Tapers.hardDaysLeft(hard, wednesday))
        assertTrue(Tapers.canTakeHardDay(hard, wednesday))
        hard = hard.copy(hardDays = hard.hardDays + wednesday)
        assertEquals(0, Tapers.hardDaysLeft(hard, monday.plusDays(6)))
        assertFalse(Tapers.canTakeHardDay(hard, monday.plusDays(6)))
        assertTrue(Tapers.canTakeHardDay(hard, monday.plusWeeks(1)))
        assertEquals(2, Tapers.hardDaysLeft(hard, monday.plusWeeks(1)))
    }

    @Test
    fun `one hard day per day`() {
        assertFalse(Tapers.canTakeHardDay(HardTimes(hardDays = setOf(monday)), monday))
    }

    @Test
    fun `a hard week is refused within 28 days of the last one`() {
        val lowered = listOf(Limits(allowances = listOf(tapered())))
        val hard = HardTimes(hardWeeks = listOf(monday.plusWeeks(2)))
        val from = Tapers.nextHardWeekFrom(hard, monday.plusWeeks(3))
        assertEquals(monday.plusWeeks(6), from)
        assertFalse(Tapers.canTakeHardWeek(lowered, hard, monday.plusWeeks(6).minusDays(1)))
        assertTrue(Tapers.canTakeHardWeek(lowered, hard, monday.plusWeeks(6)))
        assertEquals(monday, Tapers.nextHardWeekFrom(none, monday))
    }

    @Test
    fun `a hard week is refused when no taper has lowered`() {
        val limits = listOf(
            Limits(allowances = listOf(Allowance(30, Period.DAY), tapered())),
        )
        assertFalse(Tapers.canTakeHardWeek(limits, none, monday.plusDays(3)))
        assertFalse(Tapers.canTakeHardWeek(emptyList(), none, monday.plusWeeks(5)))
        assertTrue(Tapers.canTakeHardWeek(limits, none, monday.plusWeeks(1)))
    }

    // ---- editing and reporting ----------------------------------------------

    @Test
    fun `rebasing on an edit keeps today's level`() {
        val a = tapered()
        val today = monday.plusWeeks(3).plusDays(2)
        val rebased = Tapers.rebase(a, none, today)
        assertEquals(45, rebased.minutes)
        assertEquals(today, rebased.taper?.startedOn)
        assertEquals(45, level(rebased, today))
        // With a new step, the limit does not jump.
        val edited = rebased.copy(taper = rebased.taper!!.copy(stepMinutes = 10))
        assertEquals(45, level(edited, today))
        assertEquals(45, level(edited, today.plusWeeks(1).with(java.time.DayOfWeek.MONDAY).minusDays(1)))
        assertEquals(35, level(edited, monday.plusWeeks(5)))
        assertEquals(Allowance(30, Period.DAY), Tapers.rebase(Allowance(30, Period.DAY), none, today))
    }

    @Test
    fun `the earliest goal date counts good weeks and skips held ones`() {
        val a = tapered()
        // Eight steps from the first Monday: the goal week is complete on the ninth.
        assertEquals(monday.plusWeeks(8), Tapers.earliestGoalDate(a, none, monday))
        val held = HardTimes(hardDays = setOf(monday.plusDays(1)))
        assertEquals(monday.plusWeeks(9), Tapers.earliestGoalDate(a, held, monday))
        assertNull(Tapers.earliestGoalDate(a, none, monday.plusWeeks(8)))
        assertNull(Tapers.earliestGoalDate(Allowance(30, Period.DAY), none, monday))
    }

    @Test
    fun `last week is reported as lowered, held by a hard day or by a hard week`() {
        val a = tapered()
        assertEquals(
            Tapers.LastWeek.Lowered(55, 50),
            Tapers.lastWeek(a, none, monday.plusWeeks(2)),
        )
        val day = HardTimes(hardDays = setOf(monday.plusWeeks(1).plusDays(2)))
        assertEquals(
            Tapers.LastWeek.Held(Tapers.HeldBy.HARD_DAY),
            Tapers.lastWeek(a, day, monday.plusWeeks(2)),
        )
        val week = HardTimes(hardWeeks = listOf(monday.plusWeeks(1)))
        assertEquals(
            Tapers.LastWeek.Held(Tapers.HeldBy.HARD_WEEK),
            Tapers.lastWeek(a, week, monday.plusWeeks(2)),
        )
        // Nothing to report before the first full week, with no taper, or at the goal.
        assertNull(Tapers.lastWeek(a, none, monday.plusDays(3)))
        assertNull(Tapers.lastWeek(Allowance(30, Period.DAY), none, monday.plusWeeks(2)))
        assertNull(Tapers.lastWeek(a, none, monday.plusWeeks(12)))
    }

    // ---- loosening ----------------------------------------------------------

    private fun limits(a: Allowance) = Limits(allowances = listOf(a))

    private fun loosening(from: Allowance, to: Allowance, today: LocalDate = monday.plusWeeks(3)) =
        isLoosening(limits(from), limits(to), today, none)

    @Test
    fun `removing a taper, raising the goal or shrinking the step is loosening`() {
        val a = tapered()
        val today = monday.plusWeeks(3)
        assertTrue(loosening(a, Allowance(60, Period.DAY)))
        // Even removed at a level equal to today's.
        assertTrue(loosening(a, Allowance(45, Period.DAY), today))
        assertTrue(loosening(a, tapered(goal = 30)))
        assertTrue(loosening(a, tapered(step = 2)))
    }

    @Test
    fun `adding a taper, lowering the goal or a bigger step is not loosening`() {
        val a = tapered()
        assertFalse(loosening(Allowance(60, Period.DAY), a))
        assertFalse(loosening(a, tapered(goal = 10)))
        assertFalse(loosening(a, tapered(step = 10)))
        assertFalse(loosening(a, a))
    }

    @Test
    fun `an edit is compared at today's level, not the starting minutes`() {
        val today = monday.plusWeeks(3)
        val old = tapered()
        // Starting minutes lower than the old start but above today's level 45.
        val higher = Allowance(50, Period.DAY)
        assertTrue(loosening(old, higher, today))
        val rebased = Tapers.rebase(old, none, today)
        assertFalse(loosening(old, rebased, today))
        // Same taper, started higher: nothing in the goal or step changed, but today is looser.
        assertTrue(loosening(rebased, rebased.copy(minutes = 55), today))
    }

    @Test
    fun `a limit that has lowered is not loosened by a plain one at its level`() {
        val today = monday.plusWeeks(3)
        assertFalse(
            isLoosening(
                limits(Allowance(60, Period.DAY)),
                limits(tapered(minutes = 60)),
                today,
                none,
            ),
        )
    }

    // ---- storage ------------------------------------------------------------

    @Test
    fun `a taper survives the codec`() {
        val limits = Limits(
            allowances = listOf(tapered(), Allowance(10, Period.HOUR, Days.WEEKDAYS)),
        )
        assertEquals(limits, LimitsCodec.decode(LimitsCodec.encode(limits)))
    }

    @Test
    fun `stored limits from before tapers decode unchanged`() {
        val old = """{"allow":[{"m":20,"p":"DAY","d":31}]}"""
        assertEquals(
            Limits(allowances = listOf(Allowance(20, Period.DAY, Days(31)))),
            LimitsCodec.decode(old),
        )
    }

    @Test
    fun `a damaged taper decodes as no taper and the limit stays`() {
        val plain = Limits(allowances = listOf(Allowance(60, Period.DAY)))
        listOf(
            """{"allow":[{"m":60,"p":"DAY","t":"junk"}]}""",
            """{"allow":[{"m":60,"p":"DAY","t":{"g":"x","s":5,"o":"2026-01-05"}}]}""",
            """{"allow":[{"m":60,"p":"DAY","t":{"g":20,"s":5,"o":"not a date"}}]}""",
            """{"allow":[{"m":60,"p":"DAY","t":{"g":20,"s":5}}]}""",
            """{"allow":[{"m":60,"p":"DAY","t":{"g":70,"s":5,"o":"2026-01-05"}}]}""",
            """{"allow":[{"m":60,"p":"DAY","t":{"g":20,"s":0,"o":"2026-01-05"}}]}""",
            """{"allow":[{"m":60,"p":"DAY","t":[1,2]}]}""",
        ).forEach { assertEquals(it, plain, LimitsCodec.decode(it)) }
    }

    @Test
    fun `hard times survive the codec and damaged text is none taken`() {
        val hard = HardTimes(
            hardDays = setOf(monday, monday.plusDays(9)),
            hardWeeks = listOf(monday.plusWeeks(2)),
        )
        assertEquals(hard, HardTimesCodec.decode(HardTimesCodec.encode(hard)))
        assertEquals(none, HardTimesCodec.decode(null))
        assertEquals(none, HardTimesCodec.decode("{{{"))
        assertEquals(none, HardTimesCodec.decode("""{"d":"x"}"""))
        assertEquals(
            HardTimes(hardDays = setOf(monday)),
            HardTimesCodec.decode("""{"d":["2026-01-05","nope"]}"""),
        )
    }

    @Test
    fun `entries older than two years are pruned`() {
        val today = LocalDate.of(2026, 6, 1)
        val hard = HardTimes(
            hardDays = setOf(today.minusYears(2).minusDays(1), today.minusYears(2), today),
            hardWeeks = listOf(today.minusYears(3), today.minusMonths(1)),
        )
        val kept = Tapers.pruned(hard, today)
        assertEquals(setOf(today.minusYears(2), today), kept.hardDays)
        assertEquals(listOf(today.minusMonths(1)), kept.hardWeeks)
    }

    // ---- deciding -----------------------------------------------------------

    private fun at(day: LocalDate) = LocalDateTime.of(day, java.time.LocalTime.NOON)

    private fun target(a: Allowance, spentMinutes: Int, day: LocalDate) = Allowances.Target(
        "ig_reels",
        Limits(allowances = listOf(a)),
        mapOf(
            Allowances.ledgerKey("ig_reels", a.period) to
                Allowances.Spent(Allowances.periodKey(a.period, at(day)), spentMinutes * 60_000L),
        ),
    )

    @Test
    fun `a tapered daily limit blocks at its level, not at the starting minutes`() {
        val a = tapered()
        val day = monday.plusWeeks(2)
        assertTrue(Allowances.decide(listOf(target(a, 49, day)), at(day)) is Allowances.Outcome.OnTheClock)
        assertEquals(
            Allowances.Outcome.Block("ig_reels", Allowances.Reason.ALLOWANCE_SPENT),
            Allowances.decide(listOf(target(a, 50, day)), at(day)),
        )
    }

    @Test
    fun `with a hard day it blocks at the level plus the extra`() {
        val a = tapered()
        val day = monday.plusWeeks(2)
        val hard = HardTimes(hardDays = setOf(day))
        // Level 50, so 13 more (rounded from 12.5).
        val open = Allowances.decide(listOf(target(a, 62, day)), at(day), hard)
        assertEquals(60_000L, (open as Allowances.Outcome.OnTheClock).remainingMillis)
        assertEquals(
            Allowances.Outcome.Block("ig_reels", Allowances.Reason.ALLOWANCE_SPENT),
            Allowances.decide(listOf(target(a, 63, day)), at(day), hard),
        )
    }

    @Test
    fun `remaining time follows the level too`() {
        val a = tapered()
        val day = monday.plusWeeks(2)
        assertEquals(
            50 * 60_000L,
            Allowances.remainingMillis(a, Allowances.Spent.NOTHING, at(day)),
        )
    }
}
