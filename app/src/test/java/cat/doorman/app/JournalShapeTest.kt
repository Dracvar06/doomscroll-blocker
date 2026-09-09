package cat.doorman.app

import cat.doorman.app.limits.DayRecord
import cat.doorman.app.limits.Journal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The parts of the record that give the report a shape rather than a number. */
class JournalShapeTest {

    /** A Saturday, so "the week just gone" is unambiguous. */
    private val today = LocalDate.parse("2026-09-05")

    private fun day(date: String, stops: Int) = DayRecord(date = date, stops = stops)

    @Test
    fun `recent weeks end with the week just gone, oldest first`() {
        val weeks = Journal.recentWeeks(emptyList(), today, count = 4)

        assertEquals(4, weeks.size)
        assertEquals(LocalDate.parse("2026-08-03"), weeks.first().monday)
        assertEquals(LocalDate.parse("2026-08-24"), weeks.last().monday)
    }

    @Test
    fun `the week in progress is never one of them`() {
        val thisMonday = Journal.weekOf(today)
        val weeks = Journal.recentWeeks(listOf(day(today.toString(), 99)), today, count = 8)

        assertEquals(emptyList<LocalDate>(), weeks.map { it.monday }.filter { it >= thisMonday })
    }

    @Test
    fun `a week nobody recorded stays in the run as a gap`() {
        val weeks = Journal.recentWeeks(
            days = listOf(day("2026-08-11", 5), day("2026-08-25", 7)),
            today = today,
            count = 3,
        )

        assertEquals(listOf(5, 0, 7), weeks.map { it.stops })
        assertEquals(listOf(false, true, false), weeks.map { it.isEmpty })
    }

    @Test
    fun `a run of clean weeks counts as a streak`() {
        val days = listOf(
            day("2026-08-11", 5),
            day("2026-08-18", 5),
            day("2026-08-25", 5),
        )

        assertEquals(3, Journal.weeksWithoutLoosening(days, today))
    }

    @Test
    fun `a loosening ends the streak at that week`() {
        val days = listOf(
            day("2026-08-11", 5),
            DayRecord(date = "2026-08-18", stops = 5, loosenings = 1),
            day("2026-08-25", 5),
        )

        assertEquals(1, Journal.weeksWithoutLoosening(days, today))
    }

    @Test
    fun `a week Doorman did not watch is not a week it kept`() {
        val days = listOf(day("2026-08-11", 5), day("2026-08-25", 5))

        assertEquals(1, Journal.weeksWithoutLoosening(days, today))
    }

    @Test
    fun `app and screen time add up across the day and the week`() {
        var days = Journal.record(emptyList(), LocalDate.parse("2026-08-25"),
            apps = mapOf("ig" to 1_000L), screens = mapOf("ig_stories" to 600L))
        days = Journal.record(days, LocalDate.parse("2026-08-25"),
            apps = mapOf("ig" to 500L, "yt" to 200L), screens = mapOf("ig_stories" to 100L))
        days = Journal.record(days, LocalDate.parse("2026-08-26"), apps = mapOf("ig" to 1L))

        val week = Journal.week(days, LocalDate.parse("2026-08-24"))
        assertEquals(mapOf("ig" to 1_501L, "yt" to 200L), week.apps)
        assertEquals(mapOf("ig_stories" to 700L), week.screens)
    }

    @Test
    fun `the days of a week come back Monday first`() {
        val days = Journal.daysOf(
            days = listOf(day("2026-08-31", 3), day("2026-09-06", 9)),
            monday = LocalDate.parse("2026-08-31"),
        )

        assertEquals(7, days.size)
        assertEquals(3, days.first()?.stops)
        assertEquals(9, days.last()?.stops)
    }

    @Test
    fun `a day with no row is null, not a zero`() {
        val days = Journal.daysOf(
            days = listOf(DayRecord(date = "2026-09-01", stops = 0)),
            monday = LocalDate.parse("2026-08-31"),
        )

        assertNull(days[0])
        assertEquals(0, days[1]?.stops)
    }
}
