package cat.doorman.app

import cat.doorman.app.limits.Allowance
import cat.doorman.app.limits.Allowances
import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The bookkeeping behind "five minutes an hour": renewal, spending, and the
 * counting of time itself. The rules about *when* a limit applies live in
 * LimitsTest; this is about the ledger.
 */
class AllowancesTest {

    private val noon = LocalDateTime.of(2026, 9, 2, 12, 0)
    private fun spent(key: String, minutes: Int) = Allowances.Spent(key, minutes * 60_000L)

    @Test
    fun `an allowance renews when the day turns over`() {
        val yesterday = Allowances.Spent("2026-09-01", 5 * 60_000L)
        assertEquals(
            5 * 60_000L,
            Allowances.remainingMillis(Allowance(5, Period.DAY), yesterday, noon),
        )
    }

    @Test
    fun `an allowance renews when the hour turns over`() {
        val lastHour = Allowances.Spent("2026-09-02T11", 5 * 60_000L)
        assertEquals(
            5 * 60_000L,
            Allowances.remainingMillis(Allowance(5, Period.HOUR), lastHour, noon),
        )
    }

    @Test
    fun `time spent this period counts against the allowance`() {
        assertEquals(
            3 * 60_000L,
            Allowances.remainingMillis(
                Allowance(5, Period.DAY), spent("2026-09-02", 2), noon,
            ),
        )
    }

    @Test
    fun `an allowance cannot go negative`() {
        assertEquals(
            0L,
            Allowances.remainingMillis(
                Allowance(5, Period.DAY), spent("2026-09-02", 9), noon,
            ),
        )
    }

    @Test
    fun `nothing held means nothing to do`() {
        assertEquals(
            Allowances.Outcome.Allow,
            Allowances.decide(listOf(Allowances.Target("ig_feed", Limits.OFF)), noon),
        )
    }

    @Test
    fun `a screen held outright is blocked whatever the clock says`() {
        val outcome = Allowances.decide(
            listOf(
                Allowances.Target(
                    "com.instagram.android",
                    Limits(allowances = listOf(Allowance(30, Period.DAY))),
                ),
                Allowances.Target("ig_feed", Limits.BLOCKED),
            ),
            noon,
        )
        assertEquals(Allowances.Outcome.Block("ig_feed", Allowances.Reason.ALWAYS), outcome)
    }

    /**
     * The reason limits can sit on an app and a screen at once. Instagram
     * capped at thirty minutes a day and Reels at five within it: after six
     * minutes of Reels the app is still open and Reels is not.
     */
    @Test
    fun `the tighter of two allowances is the one that stops you`() {
        val ledger = mapOf(
            Allowances.ledgerKey("com.instagram.android", Period.DAY) to spent("2026-09-02", 6),
            Allowances.ledgerKey("ig_reels", Period.DAY) to spent("2026-09-02", 6),
        )
        val outcome = Allowances.decide(
            listOf(
                Allowances.Target(
                    "com.instagram.android",
                    Limits(allowances = listOf(Allowance(30, Period.DAY))),
                    ledger,
                ),
                Allowances.Target(
                    "ig_reels",
                    Limits(allowances = listOf(Allowance(5, Period.DAY))),
                    ledger,
                ),
            ),
            noon,
        )
        assertEquals(
            Allowances.Outcome.Block("ig_reels", Allowances.Reason.ALLOWANCE_SPENT),
            outcome,
        )
    }

    @Test
    fun `while both allowances have room the clock runs on both`() {
        val ledger = mapOf(
            Allowances.ledgerKey("com.instagram.android", Period.DAY) to spent("2026-09-02", 2),
            Allowances.ledgerKey("ig_reels", Period.DAY) to spent("2026-09-02", 2),
        )
        val outcome = Allowances.decide(
            listOf(
                Allowances.Target(
                    "com.instagram.android",
                    Limits(allowances = listOf(Allowance(30, Period.DAY))),
                    ledger,
                ),
                Allowances.Target(
                    "ig_reels",
                    Limits(allowances = listOf(Allowance(5, Period.DAY))),
                    ledger,
                ),
            ),
            noon,
        ) as Allowances.Outcome.OnTheClock
        assertTrue("com.instagram.android|DAY" in outcome.charge)
        assertTrue("ig_reels|DAY" in outcome.charge)
        assertEquals(3 * 60_000L, outcome.remainingMillis)
    }

    @Test
    fun `charging rolls the record over into the new period`() {
        val yesterday = Allowances.Spent("2026-09-01", 5 * 60_000L)
        val charged = Allowances.charge(yesterday, Period.DAY, 1_000L, noon)
        assertEquals("2026-09-02", charged.periodKey)
        assertEquals(1_000L, charged.millis)
    }

    /**
     * The bug this prevents: the service remembers when it last looked at the
     * screen, and that field survives the phone being put down. Crediting the
     * whole gap would spend a day's allowance overnight with the screen off.
     */
    @Test
    fun `a long gap between checks is time away, not time spent`() {
        assertEquals(0L, Allowances.creditableMillis(0L, 8 * 60 * 60 * 1000L))
        assertEquals(400L, Allowances.creditableMillis(1_000L, 1_400L))
    }

    @Test
    fun `the first check after arriving spends nothing`() {
        assertEquals(0L, Allowances.creditableMillis(null, 5_000L))
    }

    @Test
    fun `a clock that went backwards spends nothing`() {
        assertEquals(0L, Allowances.creditableMillis(9_000L, 5_000L))
    }
}
