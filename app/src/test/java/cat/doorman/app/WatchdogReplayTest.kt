package cat.doorman.app

import cat.doorman.app.limits.Allowance
import cat.doorman.app.limits.Allowances
import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.Period
import cat.doorman.app.limits.Window
import cat.doorman.app.service.AllowanceClock
import cat.doorman.app.service.ClockPlan
import cat.doorman.app.service.ClockTicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * A limit must land, and lift, on a screen that raises no events.
 *
 * Replays the service's loop with the watchdog: one ticker that evaluates every
 * second whatever the last evaluation decided. The frame in front is set by the
 * test -- readable, unreadable, or a screen let through once -- and nothing
 * else ever asks for an evaluation, which is what a video playing in silence
 * does. Wired the way `evaluateCurrentScreenNow` wires [ClockPlan] and
 * [AllowanceClock].
 */
class WatchdogReplayTest {

    private enum class Frame { LIMITED, UNREADABLE, ALLOW_ONCE }

    private var start = LocalDateTime.of(2026, 10, 17, 10, 0)
    private var now = 0L
    private fun wallClock() = start.plusNanos(now * 1_000_000)

    private val queue = mutableListOf<Pair<Long, Runnable>>()
    private fun at(t: Long, action: () -> Unit) {
        queue += t to Runnable { action() }
    }

    private fun advanceTo(t: Long) {
        while (true) {
            val next = queue.filter { it.first <= t }.minByOrNull { it.first } ?: break
            queue.remove(next)
            now = next.first
            next.second.run()
        }
        now = t
    }

    private val app = "com.example.app"
    private var limits = Limits(allowances = listOf(Allowance(10, Period.HOUR)))
    private val clock = AllowanceClock { now }
    private var ledger = emptyMap<String, Allowances.Spent>()
    private var lastFlushAt = 0L

    private var frame = Frame.LIMITED
    private var reelsClosed = false
    private var covered = false
    private var firstBlockAt: Long? = null
    private var coveredDuringAllowOnce = false

    private val ticker = ClockTicker(
        periodMillis = 1_000,
        post = { r, delay -> queue += (now + delay) to r },
        remove = { r -> queue.removeAll { it.second === r } },
    ) {
        evaluate()
        flush()
    }

    private fun targets() = listOf(
        Allowances.Target(app, limits, Allowances.withPending(ledger, clock.peek(), wallClock())),
    )

    private fun evaluate() {
        when (frame) {
            Frame.UNREADABLE -> {
                clock.stop()
                covered = false
            }
            Frame.ALLOW_ONCE -> {
                covered = false
                val charge = ClockPlan.onAllowOnce(targets(), wallClock())
                if (charge.isEmpty()) clock.stop() else clock.tick(charge)
            }
            Frame.LIMITED -> {
                val outcome = Allowances.decide(targets(), wallClock())
                covered = outcome is Allowances.Outcome.Block || reelsClosed
                if (outcome is Allowances.Outcome.Block && firstBlockAt == null) firstBlockAt = now
                val charge = ClockPlan.afterDecision(outcome, reelsClosed)
                if (charge.isEmpty()) clock.stop() else clock.tick(charge)
            }
        }
        if (frame == Frame.ALLOW_ONCE && covered) coveredDuringAllowOnce = true
        ticker.ensure()
    }

    private fun flush() {
        if (now - lastFlushAt < 10_000) return
        val pending = clock.drain()
        if (pending.isEmpty()) return
        lastFlushAt = now
        ledger = Allowances.withPending(ledger, pending, wallClock())
    }

    private fun spentMillis(): Long =
        Allowances.withPending(ledger, clock.peek(), wallClock())[Allowances.ledgerKey(app, Period.HOUR)]?.millis ?: 0L

    @Test
    fun `a silent video with one unreadable frame still reaches the block at the right second`() {
        at(299_500) { frame = Frame.UNREADABLE }
        at(300_500) { frame = Frame.LIMITED }
        evaluate()
        advanceTo(20 * 60_000L)

        val blockedAt = firstBlockAt
        assertNotNull("never blocked", blockedAt)
        // Ten minutes, plus the second the unreadable frame cost.
        assertTrue("blocked at ${blockedAt}ms, before ten minutes", blockedAt!! >= 600_000L)
        assertTrue("blocked at ${blockedAt}ms, late", blockedAt <= 603_000L)
        assertTrue(covered)
    }

    @Test
    fun `hours starting while the screen sits idle block within a tick`() {
        start = LocalDateTime.of(2026, 10, 17, 10, 29, 30)
        limits = Limits(windows = listOf(Window(10 * 60 + 30, 10 * 60 + 31)))
        evaluate()
        advanceTo(29_000L)
        assertFalse(covered)

        advanceTo(31_100L)
        assertTrue("not covered a second after the hours began", covered)
        assertTrue("covered at ${firstBlockAt}ms", firstBlockAt!! in 30_000L..31_000L)
    }

    @Test
    fun `the block lifts within a tick of the hours ending`() {
        start = LocalDateTime.of(2026, 10, 17, 10, 29, 30)
        limits = Limits(windows = listOf(Window(10 * 60 + 30, 10 * 60 + 31)))
        evaluate()
        advanceTo(60_000L)
        assertTrue(covered)

        advanceTo(91_100L)
        assertFalse("still covered a second after the hours ended", covered)
    }

    @Test
    fun `an allow-once interlude is counted but never blocked`() {
        limits = Limits(allowances = listOf(Allowance(1, Period.HOUR)))
        evaluate()
        advanceTo(30_000L)
        assertFalse(covered)

        frame = Frame.ALLOW_ONCE
        advanceTo(150_000L)
        assertFalse("covered while the screen was let through once", coveredDuringAllowOnce)
        assertTrue("counted ${spentMillis()}ms of about 150000", spentMillis() in 145_000L..150_000L)

        // Back on a screen that is held: the minute is long gone.
        frame = Frame.LIMITED
        advanceTo(152_000L)
        assertTrue(covered)
    }

    @Test
    fun `no time is charged while the reel block is up`() {
        reelsClosed = true
        evaluate()
        advanceTo(120_000L)
        assertEquals(0L, spentMillis())

        reelsClosed = false
        advanceTo(180_000L)
        assertTrue("counted ${spentMillis()}ms of about 60000", spentMillis() in 55_000L..60_000L)
    }
}
