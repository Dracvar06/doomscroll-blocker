package cat.doorman.app

import cat.doorman.app.limits.DayRecord
import cat.doorman.app.limits.Journal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The record a weekly report is built from.
 *
 * Everything here is about a report telling somebody the truth about their own
 * week. Turning over on the wrong day, or comparing a part-week against a whole
 * one, would have Doorman congratulating people every Wednesday -- and a report
 * that flatters is worse than no report, because people believe it.
 */
class JournalTest {

    private val monday = LocalDate.of(2026, 8, 31)
    private val wednesday = LocalDate.of(2026, 9, 2)
    private val sunday = LocalDate.of(2026, 9, 6)

    @Test
    fun `a week runs Monday to Sunday`() {
        assertEquals(monday, Journal.weekOf(monday))
        assertEquals(monday, Journal.weekOf(wednesday))
        assertEquals(monday, Journal.weekOf(sunday))
        assertEquals(monday.plusWeeks(1), Journal.weekOf(sunday.plusDays(1)))
    }

    @Test
    fun `the first thing that happens on a day opens the day`() {
        val days = Journal.record(emptyList(), wednesday, stops = 1)
        assertEquals(1, days.size)
        assertEquals(wednesday.toString(), days.first().date)
        assertEquals(1, days.first().stops)
    }

    /**
     * The three numbers arrive from three different places -- the service when
     * a block goes up, the service again when time is spent, the settings
     * screen when a limit is weakened -- and none of them knows about the
     * others. Recording has to add rather than replace.
     */
    @Test
    fun `records from different places add up on the same day`() {
        var days = Journal.record(emptyList(), wednesday, stops = 2)
        days = Journal.record(days, wednesday, spentMillis = 60_000)
        days = Journal.record(days, wednesday, loosenings = 1)
        days = Journal.record(days, wednesday, stops = 3)
        assertEquals(1, days.size)
        assertEquals(5, days.first().stops)
        assertEquals(60_000, days.first().spentMillis)
        assertEquals(1, days.first().loosenings)
    }

    @Test
    fun `a week adds up only its own days`() {
        var days = Journal.record(emptyList(), monday, stops = 3)
        days = Journal.record(days, sunday, stops = 4)
        days = Journal.record(days, sunday.plusDays(1), stops = 100)

        val week = Journal.week(days, monday)
        assertEquals(7, week.stops)
        assertEquals(2, week.daysRecorded)
        assertEquals(100, Journal.week(days, monday.plusWeeks(1)).stops)
    }

    @Test
    fun `a week nothing was recorded in knows that it is empty`() {
        val week = Journal.week(emptyList(), monday)
        assertTrue(week.isEmpty)
        assertEquals(0, week.stops)
    }

    /**
     * The report compares two *finished* weeks. Comparing the three days so far
     * against a full seven would tell everybody they had improved, every time,
     * until Sunday.
     */
    @Test
    fun `the report compares the two weeks that are over`() {
        var days = Journal.record(emptyList(), monday.minusWeeks(1), stops = 10)
        days = Journal.record(days, monday, stops = 6)
        days = Journal.record(days, monday.plusWeeks(1), stops = 999)

        val (justGone, before) = Journal.lastTwoWeeks(days, monday.plusWeeks(1).plusDays(2))
        assertEquals(monday, justGone.monday)
        assertEquals(6, justGone.stops)
        assertEquals(10, before.stops)
    }

    @Test
    fun `history older than ten weeks is forgotten`() {
        var days = Journal.record(emptyList(), sunday.minusDays(Journal.KEEP_DAYS + 1), stops = 1)
        days = Journal.record(days, sunday.minusDays(Journal.KEEP_DAYS - 1), stops = 1)
        days = Journal.record(days, sunday, stops = 1)

        val kept = Journal.prune(days, sunday)
        assertEquals(2, kept.size)
    }

    /**
     * A clock that jumped, or a row written in a different time zone, must not
     * be able to sit in the future inflating a week that has not happened.
     */
    @Test
    fun `a day dated in the future is dropped`() {
        val days = Journal.record(emptyList(), sunday.plusDays(3), stops = 5)
        assertTrue(Journal.prune(days, sunday).isEmpty())
    }

    @Test
    fun `rubbish in the record does not bring the report down`() {
        val days = listOf(DayRecord("not-a-date", stops = 1), DayRecord(sunday.toString(), stops = 2))
        assertEquals(1, Journal.prune(days, sunday).size)
        assertEquals(2, Journal.week(days, monday).stops)
    }

    @Test
    fun `pruning leaves the days in order`() {
        var days = Journal.record(emptyList(), sunday, stops = 1)
        days = Journal.record(days, monday, stops = 1)
        assertEquals(listOf(monday.toString(), sunday.toString()), Journal.prune(days, sunday).map { it.date })
    }
}
