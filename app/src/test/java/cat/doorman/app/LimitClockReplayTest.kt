package cat.doorman.app

import cat.doorman.app.limits.Allowance
import cat.doorman.app.limits.Allowances
import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.Period
import cat.doorman.app.service.AllowanceClock
import cat.doorman.app.service.ClockTicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * A limit must land while a video plays.
 *
 * Replays the service's clock loop on a fake clock: an evaluation every 400 ms
 * (what a playing video's changing screen produces), each one asking for the
 * tick; the tick evaluating and writing the ledger every ten seconds; decisions
 * made on the ledger with unwritten time counted in. The pieces are the ones
 * the service uses -- [ClockTicker], [AllowanceClock], [Allowances.withPending]
 * -- wired the way `startClock`, `onClockTick` and `flushClock` wire them.
 *
 * Found on a phone: a ten-minute YouTube limit let a video run twenty minutes,
 * because every evaluation pushed the tick back and it never fired.
 */
class LimitClockReplayTest {

    private val start = LocalDateTime.of(2026, 10, 17, 10, 0)
    private var now = 0L
    private fun wallClock() = start.plusNanos(now * 1_000_000)

    /** Runnables due at a time, run in order as the fake clock advances. */
    private val queue = mutableListOf<Pair<Long, Runnable>>()
    private fun advanceTo(t: Long) {
        while (true) {
            val next = queue.filter { it.first <= t }.minByOrNull { it.first } ?: break
            queue.remove(next)
            now = next.first
            next.second.run()
        }
        now = t
    }

    private val limits = Limits(allowances = listOf(Allowance(10, Period.HOUR)))
    private val target = "com.google.android.youtube"
    private val clock = AllowanceClock { now }
    private var ledger = emptyMap<String, Allowances.Spent>()
    private var lastFlushAt = 0L
    private var blockedAt: Long? = null
    private var ticks = 0

    private val ticker = ClockTicker(
        periodMillis = 1_000,
        post = { r, delay -> queue += (now + delay) to r },
        remove = { r -> queue.removeAll { it.second === r } },
    ) {
        ticks++
        evaluate()
        flush(force = false)
    }

    private fun evaluate() {
        if (blockedAt != null) return
        val spent = Allowances.withPending(ledger, clock.peek(), wallClock())
        when (val outcome = Allowances.decide(listOf(Allowances.Target(target, limits, spent)), wallClock())) {
            is Allowances.Outcome.OnTheClock -> {
                clock.tick(outcome.charge)
                ticker.ensure()
            }
            is Allowances.Outcome.Block -> {
                blockedAt = now
                ticker.cancel()
                clock.stop()
                flush(force = true)
            }
            Allowances.Outcome.Allow -> Unit
        }
    }

    private fun flush(force: Boolean) {
        if (!force && now - lastFlushAt < 10_000) return
        val pending = clock.drain()
        if (pending.isEmpty()) return
        lastFlushAt = now
        ledger = Allowances.withPending(ledger, pending, wallClock())
    }

    /** A video playing: the screen changes, and is evaluated, every 400 ms. */
    private fun playVideo(untilMillis: Long) {
        var t = now
        while (t < untilMillis && blockedAt == null) {
            advanceTo(t)
            evaluate()
            t += 400
        }
        advanceTo(untilMillis)
    }

    @Test
    fun `a ten minute limit lands at ten minutes while a video plays`() {
        playVideo(untilMillis = 20 * 60_000L)

        val at = blockedAt
        assertNotNull("never blocked", at)
        at!!
        assertTrue("blocked at ${at}ms, before ten minutes", at >= 10 * 60_000L)
        assertTrue("blocked at ${at}ms, more than two seconds late", at <= 10 * 60_000L + 2_000L)
    }

    @Test
    fun `evaluations several times a second do not hold the tick back`() {
        playVideo(untilMillis = 60_000L)

        // One a second, give or take the first.
        assertTrue("only $ticks ticks in a minute", ticks in 58..61)
    }

    @Test
    fun `the decision counts time not yet written`() {
        playVideo(untilMillis = 9_000L)

        // Nine seconds in: nothing written yet, but nine seconds spent.
        assertEquals(emptyMap<String, Allowances.Spent>(), ledger)
        val spent = Allowances.withPending(ledger, clock.peek(), wallClock())
        val millis = spent.getValue(Allowances.ledgerKey(target, Period.HOUR)).millis
        assertTrue("counted $millis ms of 9000", millis in 8_000L..9_000L)
    }
}
