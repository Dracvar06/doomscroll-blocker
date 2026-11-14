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
        counter.onScreen("ig_reels", tenAnHour, 3 * minute, persist)
        assertEquals(2, saved["ig_reels"]!!.count)
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
}
