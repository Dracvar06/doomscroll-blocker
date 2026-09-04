package cat.doorman.app

import cat.doorman.app.limits.RingGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dial's arithmetic. Everything else about a dial is drawing, which a test
 * cannot judge; this is the part with a right answer.
 */
class RingGeometryTest {

    @Test
    fun `midnight is at the top and the day runs clockwise`() {
        assertEquals(0f, RingGeometry.angleOf(0), 0.01f)
        assertEquals(90f, RingGeometry.angleOf(6 * 60), 0.01f)
        assertEquals(180f, RingGeometry.angleOf(12 * 60), 0.01f)
        assertEquals(315f, RingGeometry.angleOf(21 * 60), 0.01f)
    }

    @Test
    fun `an angle comes back as the minute it stands for`() {
        assertEquals(0, RingGeometry.minuteAt(0f))
        assertEquals(6 * 60, RingGeometry.minuteAt(90f))
        assertEquals(12 * 60, RingGeometry.minuteAt(180f))
        assertEquals(21 * 60, RingGeometry.minuteAt(315f))
    }

    @Test
    fun `minutes survive a round trip through the dial`() {
        listOf(0, 5 * 60, 9 * 60, 13 * 60 + 30, 21 * 60, 23 * 60 + 55).forEach { minute ->
            assertEquals(minute, RingGeometry.minuteAt(RingGeometry.angleOf(minute)))
        }
    }

    /**
     * The bug this prevents: a finger dragged anticlockwise past midnight gives
     * a negative angle, and Kotlin's `%` keeps the sign. Without wrapping, the
     * handle would leap to the far side of the dial at exactly the moment
     * someone is setting a night-time block.
     */
    @Test
    fun `dragging back past midnight wraps instead of going negative`() {
        assertEquals(23 * 60 + 55, RingGeometry.minuteAt(-1.25f))
        assertEquals(23 * 60, RingGeometry.minuteAt(-15f))
        assertTrue(RingGeometry.minuteAt(-90f) >= 0)
        assertEquals(18 * 60, RingGeometry.minuteAt(-90f))
    }

    @Test
    fun `an angle past a full turn is the same as the angle within one`() {
        assertEquals(RingGeometry.minuteAt(45f), RingGeometry.minuteAt(405f))
        assertEquals(RingGeometry.minuteAt(0f), RingGeometry.minuteAt(720f))
    }

    @Test
    fun `every landing point is a multiple of the snap`() {
        (0..359).forEach { degrees ->
            val minute = RingGeometry.minuteAt(degrees.toFloat())
            assertEquals("at $degrees deg", 0, minute % RingGeometry.SNAP_MINUTES)
            assertTrue("at $degrees deg", minute in 0 until 24 * 60)
        }
    }

    @Test
    fun `a touch on the screen becomes an angle from the top`() {
        // y points down on a screen, so straight up is a negative y.
        assertEquals(0f, RingGeometry.degreesFromTop(0f, -100f), 0.01f)
        assertEquals(90f, RingGeometry.degreesFromTop(100f, 0f), 0.01f)
        assertEquals(180f, RingGeometry.degreesFromTop(0f, 100f), 0.01f)
        assertEquals(270f, RingGeometry.degreesFromTop(-100f, 0f), 0.01f)
    }

    /** Distance is measured the short way round, across midnight included. */
    @Test
    fun `the gap between two points goes the short way round`() {
        assertEquals(10, RingGeometry.distanceAround(23 * 60 + 55, 5))
        assertEquals(60, RingGeometry.distanceAround(1 * 60, 2 * 60))
        assertEquals(12 * 60, RingGeometry.distanceAround(0, 12 * 60))
    }

    /**
     * Picked once when the drag starts and then held, so a handle dragged past
     * the other one does not hand the gesture over mid-swipe.
     */
    @Test
    fun `the nearer handle is the one picked up`() {
        val start = 21 * 60
        val end = 9 * 60
        assertTrue(RingGeometry.nearerHandleIsStart(22 * 60, start, end))
        assertTrue(RingGeometry.nearerHandleIsStart(23 * 60, start, end))
        assertTrue(!RingGeometry.nearerHandleIsStart(8 * 60, start, end))
        assertTrue(!RingGeometry.nearerHandleIsStart(10 * 60, start, end))
    }

    /**
     * The seam between a dial's two ends. Sliding down towards "immediate" and
     * carrying on a little too far used to land on five minutes -- and since
     * shortening the wait is itself subject to the wait, a slip of the thumb
     * cost five minutes before it could be undone.
     */
    @Test
    fun `overshooting the bottom of a dial holds at the bottom`() {
        assertEquals(0, RingGeometry.withoutCrossingSeam(290, 10, 0, 300))
        assertEquals(0, RingGeometry.withoutCrossingSeam(300, 0, 0, 300))
    }

    @Test
    fun `overshooting the top of a dial holds at the top`() {
        assertEquals(300, RingGeometry.withoutCrossingSeam(10, 290, 0, 300))
        assertEquals(300, RingGeometry.withoutCrossingSeam(0, 300, 0, 300))
    }

    /** Ordinary movement is left alone; only a jump across the seam is caught. */
    @Test
    fun `normal turning of the dial passes through untouched`() {
        assertEquals(60, RingGeometry.withoutCrossingSeam(60, 30, 0, 300))
        assertEquals(30, RingGeometry.withoutCrossingSeam(30, 60, 0, 300))
        assertEquals(150, RingGeometry.withoutCrossingSeam(150, 140, 0, 300))
    }

    /** The minutes dial starts at five rather than nothing, and still holds. */
    @Test
    fun `a dial that does not start at zero holds at its own bottom`() {
        assertEquals(5, RingGeometry.withoutCrossingSeam(55, 10, 5, 60))
        assertEquals(60, RingGeometry.withoutCrossingSeam(10, 55, 5, 60))
    }
}
