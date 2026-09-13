package cat.doorman.app

import cat.doorman.app.service.NavigationTracker
import cat.doorman.app.service.ScrollBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The subtlest behaviour in the app: a reel someone sent you must be watchable,
 * and must not become a feed. Both halves are easy to get wrong in opposite
 * directions, so both are pinned here.
 */
class ScrollBudgetTest {

    private val viewport = 2400
    private fun budget() = ScrollBudget(viewportHeightPx = { viewport })

    private val key = "com.instagram.android/ig_shared_reel"

    @Test
    fun `the reel you were sent is watchable`() {
        val b = budget()
        b.begin(key, budget = 1, nowMillis = 0)
        assertTrue(b.hasBudget(key, 0))
    }

    @Test
    fun `swiping to a second reel spends the budget`() {
        val b = budget()
        b.begin(key, budget = 1, nowMillis = 0)
        val spent = b.onScroll(key, deltaX = 0, deltaY = viewport, itemIndex = 1, nowMillis = 100)
        assertTrue("swiping on should exhaust the budget", spent)
        assertFalse(b.hasBudget(key, 100))
    }

    @Test
    fun `replaying, pausing and scrubbing do not spend it`() {
        val b = budget()
        b.begin(key, budget = 1, nowMillis = 0)
        repeat(20) { b.onScroll(key, deltaX = 0, deltaY = 0, itemIndex = 0, nowMillis = 100L + it) }
        assertTrue("watching the same reel must stay allowed", b.hasBudget(key, 200))
    }

    /** Sideways opens a profile. Blocking that would be a false positive. */
    @Test
    fun `horizontal swipes do not spend it`() {
        val b = budget()
        b.begin(key, budget = 1, nowMillis = 0)
        val spent = b.onScroll(key, deltaX = 900, deltaY = 20, itemIndex = null, nowMillis = 100)
        assertFalse(spent)
        assertTrue(b.hasBudget(key, 100))
    }

    /** Compose feeds report indices erratically, so distance is the backstop. */
    @Test
    fun `scroll distance alone spends it when no item index is reported`() {
        val b = budget()
        b.begin(key, budget = 1, nowMillis = 0)
        assertFalse(b.onScroll(key, 0, 200, null, 10))
        val spent = b.onScroll(key, 0, viewport, null, 20)
        assertTrue(spent)
    }

    /**
     * The numbers are the ones YouTube actually reports on a Pixel 10: a Shorts
     * page turn is 1031px on a recycler 2062px tall, inside a 2424px screen.
     * Judged against the screen that is not even half a swipe, so the first
     * swipe past a Short someone sent counted for nothing and a second Short
     * played free -- which is what a user reported.
     */
    @Test
    fun `a page turn on a pager shorter than the screen spends it`() {
        val b = budget()
        b.begin(key, budget = 1, nowMillis = 0)
        val spent = b.onScroll(
            key, deltaX = 0, deltaY = 1031, itemIndex = null, nowMillis = 10,
            scrolledViewHeightPx = 2062,
        )
        assertTrue("one swipe is one Short", spent)
    }

    /** A nudge is still a nudge, whichever view reported it. */
    @Test
    fun `a small drag inside a short pager does not spend it`() {
        val b = budget()
        b.begin(key, budget = 1, nowMillis = 0)
        assertFalse(
            b.onScroll(
                key, deltaX = 0, deltaY = 180, itemIndex = null, nowMillis = 10,
                scrolledViewHeightPx = 2062,
            ),
        )
        assertTrue(b.hasBudget(key, 20))
    }

    /**
     * The swipe is the better signal, but YouTube drops it often enough that a
     * Short someone sent would otherwise roll into the next one for as long as
     * the user let it. The clock is the floor under that.
     */
    @Test
    fun `an allowance with a ceiling runs out on its own`() {
        val b = budget()
        b.begin(key, budget = 1, nowMillis = 0, allowMillis = 120_000)
        assertTrue(b.hasBudget(key, 119_000))
        assertEquals(1_000L, b.remainingMillis(key, 119_000))
        assertFalse(b.hasBudget(key, 121_000))
    }

    /** Stepping out of the app must not wind the clock back. */
    @Test
    fun `the ceiling counts from the first arrival, not the latest`() {
        val b = budget()
        b.begin(key, budget = 1, nowMillis = 0, allowMillis = 120_000)
        // Back within the session gap: the same allowance, older by a minute.
        b.begin(key, budget = 1, nowMillis = 60_000, allowMillis = 120_000)
        assertFalse(b.hasBudget(key, 121_000))
    }

    /** Without a ceiling, nothing changes: the swipe alone ends it. */
    @Test
    fun `an allowance with no ceiling never expires by itself`() {
        val b = budget()
        b.begin(key, budget = 1, nowMillis = 0)
        assertTrue(b.hasBudget(key, 10 * 60 * 1000))
        assertNull(b.remainingMillis(key, 10 * 60 * 1000))
    }

    @Test
    fun `leaving and coming back does not refill the budget`() {
        val b = budget()
        b.begin(key, budget = 1, nowMillis = 0)
        b.onScroll(key, 0, viewport, 1, 100)
        assertFalse(b.hasBudget(key, 100))

        // Back 30 seconds later: still spent.
        b.begin(key, budget = 1, nowMillis = 30_000)
        assertFalse("app-switching must not be a way to keep scrolling", b.hasBudget(key, 30_000))
    }

    /**
     * A friend sending three reels in a row is not doomscrolling. Going back to
     * the conversation and opening the next one resets the budget explicitly --
     * the service calls reset() when the previous screen was something else --
     * so each reel someone sent is watchable.
     */
    @Test
    fun `an explicit reset gives the next sent reel its own budget`() {
        val b = budget()
        b.begin(key, budget = 1, nowMillis = 0)
        b.onScroll(key, 0, viewport, 1, 100)
        assertFalse(b.hasBudget(key, 100))

        b.reset()
        b.begin(key, budget = 1, nowMillis = 200)
        assertTrue("the next reel they sent must also play", b.hasBudget(key, 200))
    }

    @Test
    fun `a genuinely new visit much later starts fresh`() {
        val b = budget()
        b.begin(key, budget = 1, nowMillis = 0)
        b.onScroll(key, 0, viewport, 1, 100)
        b.begin(key, budget = 1, nowMillis = 10 * 60 * 1000)
        assertTrue("a new reel opened later deserves its own budget", b.hasBudget(key, 10 * 60 * 1000))
    }
}

class NavigationTrackerTest {

    @Test
    fun `arriving from another app reads as external`() {
        val t = NavigationTracker()
        t.record("com.whatsapp", null, 0)
        t.record("com.instagram.android", "ig_shared_reel", 100)
        assertEquals(
            NavigationTracker.Entry.External,
            t.entryFor("com.instagram.android", "ig_shared_reel"),
        )
    }

    @Test
    fun `arriving from a conversation names that screen`() {
        val t = NavigationTracker()
        t.record("com.instagram.android", "ig_dm_thread", 0)
        t.record("com.instagram.android", "ig_shared_reel", 100)
        assertEquals(
            NavigationTracker.Entry.From("ig_dm_thread"),
            t.entryFor("com.instagram.android", "ig_shared_reel"),
        )
    }

    @Test
    fun `arriving from the reels tab is browsing, not a shared reel`() {
        val t = NavigationTracker()
        t.record("com.instagram.android", "ig_reels", 0)
        t.record("com.instagram.android", "ig_shared_reel", 100)
        assertEquals(
            NavigationTracker.Entry.From("ig_reels"),
            t.entryFor("com.instagram.android", "ig_shared_reel"),
        )
    }

    /** Repeated events for one screen must not erase where you came from. */
    @Test
    fun `repeated visits to the same screen keep the earlier origin`() {
        val t = NavigationTracker()
        t.record("com.instagram.android", "ig_dm_thread", 0)
        t.record("com.instagram.android", "ig_shared_reel", 100)
        repeat(5) { t.record("com.instagram.android", "ig_shared_reel", 200L + it) }
        assertEquals(
            NavigationTracker.Entry.From("ig_dm_thread"),
            t.entryFor("com.instagram.android", "ig_shared_reel"),
        )
    }
}
