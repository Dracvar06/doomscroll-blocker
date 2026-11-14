package cat.doorman.app

import cat.doorman.app.limits.Allowance
import cat.doorman.app.limits.Allowances
import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.Period
import cat.doorman.app.limits.Window
import cat.doorman.app.service.AllowanceClock
import cat.doorman.app.service.ClockPlan
import cat.doorman.app.service.NavigationTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class AllowanceClockTest {

    private var now = 0L
    private val clock = AllowanceClock { now }

    @Test
    fun `a twenty second gap on the same target credits the cap and no more`() {
        clock.tick(listOf("a|HOUR"))
        now = 20_000L
        clock.tick(listOf("a|HOUR"))

        assertEquals(Allowances.MAX_CREDITED_GAP_MS, clock.pendingMillis())
    }

    @Test
    fun `a gap that spans leaving the app credits nothing`() {
        clock.tick(listOf("a|HOUR"))
        now = 20_000L
        clock.stop()

        assertEquals(0L, clock.pendingMillis())
    }

    @Test
    fun `a long gap before arriving at another target credits nothing`() {
        clock.tick(listOf("a|HOUR"))
        now = 20_000L
        clock.tick(listOf("b|HOUR"))

        assertEquals(0L, clock.pendingMillis())
    }

    @Test
    fun `a gap within the cap is credited whole`() {
        clock.tick(listOf("a|HOUR"))
        now = 3_000L
        clock.stop()

        assertEquals(3_000L, clock.pendingMillis())
    }

    @Test
    fun `a clock that goes backwards credits nothing`() {
        now = 10_000L
        clock.tick(listOf("a|HOUR"))
        now = 4_000L
        clock.tick(listOf("a|HOUR"))

        assertEquals(0L, clock.pendingMillis())
    }

    // ---- what the clock is told to charge

    private val noon = LocalDateTime.of(2026, 10, 17, 12, 0)
    private val spentHour = Allowances.Spent("2026-10-17T12", 5 * 60_000L)

    @Test
    fun `a screen let through once is charged to the app even when its allowance is spent`() {
        val limits = Limits(allowances = listOf(Allowance(5, Period.HOUR)))
        val target = Allowances.Target(
            "app", limits, mapOf(Allowances.ledgerKey("app", Period.HOUR) to spentHour),
        )

        assertTrue(Allowances.decide(listOf(target), noon) is Allowances.Outcome.Block)
        assertEquals(listOf("app|HOUR"), ClockPlan.onAllowOnce(listOf(target), noon))
    }

    @Test
    fun `a screen let through once is charged to its own allowance as well`() {
        val app = Allowances.Target("app", Limits(allowances = listOf(Allowance(5, Period.DAY))))
        val screen = Allowances.Target("reels", Limits(allowances = listOf(Allowance(5, Period.HOUR))))

        assertEquals(
            listOf("app|DAY", "reels|HOUR"),
            ClockPlan.onAllowOnce(listOf(app, screen), noon),
        )
    }

    @Test
    fun `hours alone give a screen let through once nothing to charge`() {
        val target = Allowances.Target("app", Limits(windows = listOf(Window(0, 23 * 60))))

        assertEquals(emptyList<String>(), ClockPlan.onAllowOnce(listOf(target), noon))
    }

    @Test
    fun `the clock stands still while the reel block is up`() {
        val running = Allowances.Outcome.OnTheClock(listOf("app|HOUR"), 60_000L)

        assertEquals(emptyList<String>(), ClockPlan.afterDecision(running, reelsClosed = true))
        assertEquals(listOf("app|HOUR"), ClockPlan.afterDecision(running, reelsClosed = false))
        assertEquals(
            emptyList<String>(),
            ClockPlan.afterDecision(Allowances.Outcome.Allow, reelsClosed = false),
        )
    }

    @Test
    fun `only allowances and hours make a time limit`() {
        assertTrue(Allowances.hasTimeLimit(listOf(Limits.OFF, Limits(allowances = listOf(Allowance(5, Period.DAY))))))
        assertTrue(Allowances.hasTimeLimit(listOf(Limits(windows = listOf(Window(0, 60))))))
        assertFalse(Allowances.hasTimeLimit(listOf(Limits.OFF, Limits.BLOCKED)))
    }

    // A single video opened from a screen the user limits is that screen going on.

    private val feedLimits = Limits(allowances = listOf(Allowance(5, Period.DAY)))
    private val feedKey = "ig_feed|DAY"
    private val fromFeed = NavigationTracker.Entry.From("ig_feed")
    private val enabled = setOf("ig_feed", "ig_single_reel", "ig_dm_thread")
    private val limitsByScreen = mapOf("ig_feed" to feedLimits)
    private val appOnly = listOf(
        Allowances.Target("com.instagram.android", Limits(allowances = listOf(Allowance(60, Period.DAY)))),
    )

    private fun spentFeed(minutes: Long) = mapOf(
        feedKey to Allowances.Spent(Allowances.periodKey(Period.DAY, noon), minutes * 60_000L),
    )

    @Test
    fun `from a limited feed with time left the video is counted on the feed's keys`() {
        val origin = ClockPlan.originOf(fromFeed, enabled, limitsByScreen, spentFeed(1))

        val plan = ClockPlan.onSingleVideo(origin, appOnly, noon)

        assertEquals(
            ClockPlan.SingleVideo.Counted(listOf(feedKey, "com.instagram.android|DAY")),
            plan,
        )
    }

    @Test
    fun `from a limited feed that is spent the video is blocked`() {
        val origin = ClockPlan.originOf(fromFeed, enabled, limitsByScreen, spentFeed(5))

        val plan = ClockPlan.onSingleVideo(origin, appOnly, noon)

        assertEquals(
            ClockPlan.SingleVideo.Closed(
                Allowances.Outcome.Block("ig_feed", Allowances.Reason.ALLOWANCE_SPENT),
            ),
            plan,
        )
    }

    @Test
    fun `from a feed in its blocked hours the video is blocked`() {
        val hours = mapOf("ig_feed" to Limits(windows = listOf(Window(11 * 60, 13 * 60))))
        val origin = ClockPlan.originOf(fromFeed, enabled, hours, emptyMap())

        val plan = ClockPlan.onSingleVideo(origin, appOnly, noon)

        assertEquals(
            ClockPlan.SingleVideo.Closed(Allowances.Outcome.Block("ig_feed", Allowances.Reason.SCHEDULE)),
            plan,
        )
    }

    @Test
    fun `from a conversation the video is let through and only the app keys are counted`() {
        val origin = ClockPlan.originOf(
            NavigationTracker.Entry.From("ig_dm_thread"), enabled, limitsByScreen, spentFeed(5),
        )

        assertNull(origin)
        assertEquals(
            ClockPlan.SingleVideo.Counted(listOf("com.instagram.android|DAY")),
            ClockPlan.onSingleVideo(origin, appOnly, noon),
        )
    }

    @Test
    fun `an origin nobody knows is no origin`() {
        for (entry in listOf(NavigationTracker.Entry.InApp, NavigationTracker.Entry.External)) {
            val origin = ClockPlan.originOf(entry, enabled, limitsByScreen, spentFeed(5))
            assertNull(origin)
            assertTrue(ClockPlan.onSingleVideo(origin, appOnly, noon) is ClockPlan.SingleVideo.Counted)
        }
    }

    @Test
    fun `a link from another app is let through even with the feed spent`() {
        val origin = ClockPlan.originOf(
            NavigationTracker.Entry.External, enabled, limitsByScreen, spentFeed(5),
        )

        assertEquals(
            ClockPlan.SingleVideo.Counted(listOf("com.instagram.android|DAY")),
            ClockPlan.onSingleVideo(origin, appOnly, noon),
        )
    }

    @Test
    fun `a feed whose switch is off or that is held outright is not an origin`() {
        assertNull(ClockPlan.originOf(fromFeed, enabled - "ig_feed", limitsByScreen, emptyMap()))
        assertNull(ClockPlan.originOf(fromFeed, enabled, mapOf("ig_feed" to Limits.BLOCKED), emptyMap()))
        assertNull(ClockPlan.originOf(fromFeed, enabled, mapOf("ig_feed" to Limits.OFF), emptyMap()))
    }
}
