package cat.doorman.app

import cat.doorman.app.limits.ReelQuota
import cat.doorman.app.limits.ReelWindow
import cat.doorman.app.service.ReelCounter
import cat.doorman.app.service.ReelCounter.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The visit, which is what the quota arithmetic cannot know: looking at the
 * same screen again is not a second reel, and a return after a pause is.
 */
class ReelCounterTest {

    private val minute = 60_000L
    private val quarterHour = ReelQuota(1, 15)
    private val tenAnHour = ReelQuota(10, 60)
    private val saved = mutableMapOf<String, ReelWindow>()
    private val persist: (String, ReelWindow) -> Unit = { id, w -> saved[id] = w }

    private fun counter() = ReelCounter().also { it.load(saved.toMap()) }

    @Test
    fun `looking at a screen again is the same visit`() {
        val counter = counter()
        counter.onScreen("ig_reels", tenAnHour, 0L, persist)
        repeat(50) { i ->
            counter.onScreen("ig_reels", tenAnHour, (i + 1) * 1_000L, persist)
        }
        // Fifty glances, one reel.
        assertEquals(1, saved["ig_reels"]!!.count)
    }

    @Test
    fun `a swipe past the only reel closes the screen and the visit stays closed`() {
        val counter = counter()
        assertEquals(State.Open(0), counter.onScreen("ig_reels", quarterHour, 0L, persist))
        val closed = counter.onSwipe("ig_reels", quarterHour, 5_000L, persist)
        assertEquals(State.Closed(15 * minute), closed)
        // The next look at the screen finds it closed, not merely out of reels.
        assertEquals(
            State.Closed(15 * minute),
            counter.onScreen("ig_reels", quarterHour, 6_000L, persist),
        )
    }

    @Test
    fun `a pause on the last reel does not close it`() {
        val counter = counter()
        counter.onScreen("ig_reels", quarterHour, 0L, persist)
        assertEquals(State.Open(0), counter.onScreen("ig_reels", quarterHour, 90_000L, persist))
    }

    @Test
    fun `coming back after a break counts a reel and finds a spent quota closed`() {
        val counter = counter()
        counter.onScreen("ig_reels", quarterHour, 0L, persist)
        counter.reset()
        assertEquals(
            State.Closed(15 * minute),
            counter.onScreen("ig_reels", quarterHour, 10 * minute, persist),
        )
        assertTrue(saved["ig_reels"]!!.over)
    }

    @Test
    fun `a gap of more than two minutes is a new visit`() {
        val counter = counter()
        counter.onScreen("ig_reels", tenAnHour, 0L, persist)
        counter.onLeft(1 * minute)
        counter.onScreen("ig_reels", tenAnHour, 4 * minute, persist)
        assertEquals(2, saved["ig_reels"]!!.count)
    }

    @Test
    fun `a gap of under two minutes away is the same visit`() {
        val counter = counter()
        counter.onScreen("ig_reels", tenAnHour, 0L, persist)
        counter.onLeft(10 * minute)
        counter.onScreen("ig_reels", tenAnHour, 11 * minute, persist)
        assertEquals(1, saved["ig_reels"]!!.count)
    }

    @Test
    fun `one reel watched for a long time is not counted twice`() {
        val counter = counter()
        counter.onScreen("ig_reels", tenAnHour, 0L, persist)
        // Nothing raises an event for ten minutes, then a tap to pause.
        counter.onScreen("ig_reels", tenAnHour, 10 * minute, persist)
        assertEquals(1, saved["ig_reels"]!!.count)
    }

    @Test
    fun `coming back clears the time away`() {
        val counter = counter()
        counter.onScreen("ig_reels", tenAnHour, 0L, persist)
        counter.onLeft(1 * minute)
        counter.onScreen("ig_reels", tenAnHour, 2 * minute, persist)
        // Back on the screen for a long while: the earlier trip does not count against it.
        counter.onScreen("ig_reels", tenAnHour, 20 * minute, persist)
        assertEquals(1, saved["ig_reels"]!!.count)
    }

    @Test
    fun `scroll events right after a counted swipe belong to it`() {
        val counter = counter()
        counter.onScreen("ig_reels", tenAnHour, 0L, persist)
        assertEquals(false, counter.settling(1_000L))
        counter.onSwipe("ig_reels", tenAnHour, 1_000L, persist)
        assertEquals(true, counter.settling(1_000L))
        assertEquals(true, counter.settling(1_399L))
        assertEquals(false, counter.settling(1_400L))
    }

    @Test
    fun `books loaded late do not replace a reel counted before they arrived`() {
        val counter = ReelCounter()
        counter.onScreen("ig_reels", tenAnHour, 10 * minute, persist)
        counter.load(
            mapOf(
                "ig_reels" to ReelWindow(0L, 4),
                "yt_shorts" to ReelWindow(0L, 2),
            ),
        )
        assertEquals(ReelWindow(10 * minute, 1), counter.window("ig_reels"))
        assertEquals(ReelWindow(0L, 2), counter.window("yt_shorts"))
    }

    @Test
    fun `a stretch stored as newer than the one in memory wins the load`() {
        val counter = ReelCounter()
        counter.onScreen("ig_reels", tenAnHour, 0L, persist)
        counter.load(mapOf("ig_reels" to ReelWindow(5 * minute, 3)))
        assertEquals(ReelWindow(5 * minute, 3), counter.window("ig_reels"))
    }

    @Test
    fun `only a view that fills most of the screen can be the pager`() {
        assertTrue(ReelCounter.isPagerSized(2062, 2424))
        assertTrue(ReelCounter.isPagerSized(null, 2424))
        // A comments list.
        assertEquals(false, ReelCounter.isPagerSized(1200, 2424))
    }

    @Test
    fun `the block lifting under somebody counts the reel they are on`() {
        val counter = counter()
        counter.onScreen("ig_reels", quarterHour, 0L, persist)
        counter.onSwipe("ig_reels", quarterHour, 1_000L, persist)
        // They sit on the block, glancing now and then; it lifts at 15:00.
        counter.onScreen("ig_reels", quarterHour, 14 * minute, persist)
        val lifted = counter.onScreen("ig_reels", quarterHour, 15 * minute + 1, persist)
        assertEquals(State.Open(0), lifted)
        assertEquals(ReelWindow(15 * minute + 1, 1), saved["ig_reels"])
    }

    @Test
    fun `a swipe before the screen has been looked at is ignored`() {
        val counter = counter()
        assertNull(counter.onSwipe("ig_reels", quarterHour, 0L, persist))
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `screens keep separate books`() {
        val counter = counter()
        counter.onScreen("ig_reels", quarterHour, 0L, persist)
        counter.onSwipe("ig_reels", quarterHour, 1_000L, persist)
        counter.reset()
        // Shorts has its own quota, untouched by Instagram's.
        assertEquals(State.Open(0), counter.onScreen("yt_shorts", quarterHour, 2_000L, persist))
    }

    @Test
    fun `what was stored is what the next service picks up`() {
        val first = counter()
        first.onScreen("ig_reels", tenAnHour, 0L, persist)
        first.onSwipe("ig_reels", tenAnHour, 1_000L, persist)

        // The service is killed and comes back, inside the same stretch.
        val second = counter()
        second.onScreen("ig_reels", tenAnHour, 30 * minute, persist)
        assertEquals(3, saved["ig_reels"]!!.count)
    }

    @Test
    fun `an allowance of two shows two reels and blocks the swipe to a third`() {
        val two = ReelQuota(2, 45)
        val counter = counter()
        // Arriving shows reel 1.
        assertEquals(State.Open(1), counter.onScreen("ig_reels", two, 0L, persist))
        // The swipe to reel 2 is allowed, and reel 2 stays watchable.
        assertNull(counter.onSwipe("ig_reels", two, 5_000L, persist))
        assertEquals(State.Open(0), counter.onScreen("ig_reels", two, 60_000L, persist))
        assertEquals(2, saved["ig_reels"]!!.count)
        // The swipe that would show reel 3 is the one that closes.
        assertTrue(counter.onSwipe("ig_reels", two, 70_000L, persist) != null)
    }

    @Test
    fun `raising the quota lifts a block at once and counts the reel on screen`() {
        val counter = counter()
        val two = ReelQuota(2, 45)
        val three = ReelQuota(3, 45)
        counter.onScreen("ig_reels", two, 0L, persist)
        counter.onSwipe("ig_reels", two, 1_000L, persist)
        assertTrue(counter.onSwipe("ig_reels", two, 2_000L, persist) != null)
        assertTrue(counter.onScreen("ig_reels", two, 3_000L, persist) is State.Closed)

        assertEquals(State.Open(0), counter.onScreen("ig_reels", three, 4_000L, persist))
        assertEquals(3, saved["ig_reels"]!!.count)
        assertEquals(false, saved["ig_reels"]!!.over)
        // The third reel is the last: the next swipe closes again.
        assertTrue(counter.onSwipe("ig_reels", three, 90_000L, persist) != null)
    }

    @Test
    fun `raising the quota lifts a block on a return after a break too`() {
        val counter = counter()
        val one = ReelQuota(1, 45)
        counter.onScreen("ig_reels", one, 0L, persist)
        assertTrue(counter.onSwipe("ig_reels", one, 1_000L, persist) != null)
        counter.onLeft(2_000L)
        val state = counter.onScreen("ig_reels", ReelQuota(2, 45), 10 * minute, persist)
        assertEquals(State.Open(0), state)
    }

    @Test
    fun `a raise that still leaves the count at the quota keeps the block`() {
        val counter = counter()
        counter.onScreen("ig_reels", ReelQuota(2, 45), 0L, persist)
        counter.onSwipe("ig_reels", ReelQuota(2, 45), 1_000L, persist)
        counter.onSwipe("ig_reels", ReelQuota(2, 45), 2_000L, persist)
        assertTrue(counter.onScreen("ig_reels", ReelQuota(2, 45), 3_000L, persist) is State.Closed)
    }

    @Test
    fun `lowering the quota closes nothing until the next swipe`() {
        val counter = counter()
        val three = ReelQuota(3, 45)
        val one = ReelQuota(1, 45)
        counter.onScreen("ig_reels", three, 0L, persist)
        counter.onSwipe("ig_reels", three, 1_000L, persist)
        assertEquals(State.Open(0), counter.onScreen("ig_reels", one, 2_000L, persist))
        assertTrue(counter.onSwipe("ig_reels", one, 3_000L, persist) != null)
    }
}
