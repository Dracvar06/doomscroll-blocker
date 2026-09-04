package cat.doorman.app

import cat.doorman.app.limits.DialScale
import cat.doorman.app.limits.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ladder of durations a budget dial turns through.
 *
 * The point of the whole thing is that one turn of a thumb has to serve both
 * "two minutes an hour" and "thirty hours a week", so what is tested here is
 * that both ends stay reachable and that the numbers in between are ones a
 * person would actually choose.
 */
class DialScaleTest {

    @Test
    fun `each period reaches as far as it should`() {
        assertEquals(60, DialScale.maxMinutes(Period.HOUR))
        assertEquals(6 * 60, DialScale.maxMinutes(Period.DAY))
        assertEquals(42 * 60, DialScale.maxMinutes(Period.WEEK))
    }

    @Test
    fun `a dial starts at its shortest budget and ends exactly on its maximum`() {
        Period.entries.forEach { period ->
            val positions = DialScale.positions(period)
            assertEquals("$period", DialScale.shortestMinutes(period), positions.first())
            assertEquals("$period", DialScale.maxMinutes(period), positions.last())
        }
    }

    /**
     * Under a quarter of an hour every single minute is on the dial. One minute
     * an hour and two minutes an hour are genuinely different rules -- a glance
     * against a visit -- and a dial that cannot tell them apart is no use for
     * the tightest budgets, which are the ones people set when they mean it.
     */
    @Test
    fun `the first quarter of an hour is offered a minute at a time`() {
        listOf(Period.HOUR, Period.DAY).forEach { period ->
            val positions = DialScale.positions(period)
            assertEquals("$period", (1..15).toList(), positions.take(15))
        }
    }

    /** Past that, fives. Nobody sets seventeen minutes an hour. */
    @Test
    fun `an hour runs by single minutes and then by fives`() {
        assertEquals(
            (1..15).toList() + (20..60 step 5).toList(),
            DialScale.positions(Period.HOUR),
        )
    }

    /**
     * A minute a week is not a budget anybody means, and fourteen of them at
     * the bottom would crowd out the hours the weekly dial is there for.
     */
    @Test
    fun `a weekly dial does not bother with single minutes`() {
        assertEquals(5, DialScale.positions(Period.WEEK).first())
    }

    /**
     * The ask in one test: the beginning has to count for less than the end.
     * Without it, either the first minutes are unreachable on a weekly dial or
     * thirty hours takes thirty turns of the wrist.
     */
    @Test
    fun `the numbers accelerate and never go backwards`() {
        Period.entries.forEach { period ->
            val positions = DialScale.positions(period)
            val gaps = positions.zipWithNext { a, b -> b - a }
            gaps.zipWithNext { earlier, later ->
                assertTrue("$period: $earlier then $later", later >= earlier)
            }
            positions.zipWithNext { a, b -> assertTrue("$period", b > a) }
        }
    }

    /**
     * A dial nobody wants to turn is a dial nobody uses. Few enough rungs that
     * each is a comfortable arc, enough that the numbers stay useful.
     */
    @Test
    fun `every dial has a sensible number of stops`() {
        Period.entries.forEach { period ->
            val count = DialScale.positions(period).size
            assertTrue("$period has $count", count in 20..60)
        }
    }

    @Test
    fun `past a quarter of an hour every stop is a round five minutes`() {
        Period.entries.forEach { period ->
            DialScale.positions(period).filter { it > 15 }.forEach { minutes ->
                assertEquals("$period: $minutes", 0, minutes % 5)
            }
        }
    }

    @Test
    fun `a budget finds its own rung`() {
        val week = DialScale.positions(Period.WEEK)
        assertEquals(0, DialScale.nearestIndex(week, 5))
        assertEquals(week.size - 1, DialScale.nearestIndex(week, 42 * 60))
        week.forEachIndexed { index, minutes ->
            assertEquals(index, DialScale.nearestIndex(week, minutes))
        }
    }

    /** A typed budget lands between rungs, and the handle still has to go somewhere. */
    @Test
    fun `a value between two rungs takes the nearer one`() {
        val week = DialScale.positions(Period.WEEK)
        assertEquals(600, week[DialScale.nearestIndex(week, 610)])
        val day = DialScale.positions(Period.DAY)
        assertEquals(35, day[DialScale.nearestIndex(day, 37)])
    }

    /**
     * Moving "three hours a day" to "per hour" has to land somewhere, and an
     * hour is the most that period can mean; otherwise the handle sits off the
     * end of its own dial.
     */
    @Test
    fun `a budget is cut down to fit the period it moves to`() {
        assertEquals(60, DialScale.clampTo(3 * 60, Period.HOUR))
        assertEquals(6 * 60, DialScale.clampTo(20 * 60, Period.DAY))
        assertEquals(20, DialScale.clampTo(20, Period.HOUR))
        assertEquals(1, DialScale.clampTo(0, Period.DAY))
        assertEquals(5, DialScale.clampTo(0, Period.WEEK))
    }
}
