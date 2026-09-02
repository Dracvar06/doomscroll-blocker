package cat.doorman.app

import cat.doorman.app.limits.Allowances
import cat.doorman.app.limits.BlockMode
import cat.doorman.app.limits.Period
import cat.doorman.app.limits.dailyMinutes
import cat.doorman.app.limits.isLoosening
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The bookkeeping behind "five minutes an hour". Every awkward case here -- the
 * turn of the hour, two allowances at once, the phone spending the night on a
 * bedside table -- is one that would otherwise need a stopwatch and a day of
 * waiting to try out.
 */
class AllowancesTest {

    private val noon = LocalDateTime.of(2026, 9, 2, 12, 0)
    private fun spent(key: String, minutes: Int) =
        Allowances.Spent(key, minutes * 60_000L)

    @Test
    fun `an allowance renews when the day turns over`() {
        val yesterday = Allowances.Spent("2026-09-01", 5 * 60_000L)
        val mode = BlockMode.Allowance(5, Period.DAY)
        assertEquals(5 * 60_000L, Allowances.remainingMillis(mode, yesterday, noon))
    }

    @Test
    fun `an allowance renews when the hour turns over`() {
        val lastHour = Allowances.Spent("2026-09-02T11", 5 * 60_000L)
        val mode = BlockMode.Allowance(5, Period.HOUR)
        assertEquals(5 * 60_000L, Allowances.remainingMillis(mode, lastHour, noon))
    }

    @Test
    fun `time spent this period counts against the allowance`() {
        val mode = BlockMode.Allowance(5, Period.DAY)
        val used = spent("2026-09-02", 2)
        assertEquals(3 * 60_000L, Allowances.remainingMillis(mode, used, noon))
    }

    @Test
    fun `an allowance cannot go negative`() {
        val mode = BlockMode.Allowance(5, Period.DAY)
        assertEquals(0L, Allowances.remainingMillis(mode, spent("2026-09-02", 9), noon))
    }

    @Test
    fun `nothing held means nothing to do`() {
        assertEquals(
            Allowances.Outcome.Allow,
            Allowances.decide(listOf(Allowances.Target("ig_feed", BlockMode.Off)), noon),
        )
    }

    @Test
    fun `a screen held outright is blocked whatever the clock says`() {
        val outcome = Allowances.decide(
            listOf(
                Allowances.Target("com.instagram.android", BlockMode.Allowance(30, Period.DAY)),
                Allowances.Target("ig_feed", BlockMode.Blocked),
            ),
            noon,
        )
        assertEquals(Allowances.Outcome.Block("ig_feed", allowanceSpent = false), outcome)
    }

    /**
     * The reason both levels exist. Someone caps Instagram at thirty minutes a
     * day and Reels at five within it; after six minutes of Reels the app is
     * still open to them and Reels is not.
     */
    @Test
    fun `the tighter of two allowances is the one that stops you`() {
        val outcome = Allowances.decide(
            listOf(
                Allowances.Target(
                    "com.instagram.android",
                    BlockMode.Allowance(30, Period.DAY),
                    spent("2026-09-02", 6),
                ),
                Allowances.Target(
                    "ig_reels",
                    BlockMode.Allowance(5, Period.DAY),
                    spent("2026-09-02", 6),
                ),
            ),
            noon,
        )
        assertEquals(Allowances.Outcome.Block("ig_reels", allowanceSpent = true), outcome)
    }

    @Test
    fun `while both allowances have room the clock runs on both`() {
        val outcome = Allowances.decide(
            listOf(
                Allowances.Target(
                    "com.instagram.android",
                    BlockMode.Allowance(30, Period.DAY),
                    spent("2026-09-02", 2),
                ),
                Allowances.Target(
                    "ig_reels",
                    BlockMode.Allowance(5, Period.DAY),
                    spent("2026-09-02", 2),
                ),
            ),
            noon,
        ) as Allowances.Outcome.OnTheClock
        assertEquals(listOf("com.instagram.android", "ig_reels"), outcome.charge)
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
     * whole gap would spend a day's allowance overnight without the screen ever
     * being on.
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

    @Test
    fun `modes sort from most room to least`() {
        assertTrue(BlockMode.Off.dailyMinutes > BlockMode.Allowance(5, Period.HOUR).dailyMinutes)
        assertTrue(
            BlockMode.Allowance(5, Period.HOUR).dailyMinutes >
                BlockMode.Allowance(30, Period.DAY).dailyMinutes,
        )
        assertTrue(
            BlockMode.Allowance(5, Period.DAY).dailyMinutes > BlockMode.Blocked.dailyMinutes,
        )
    }

    /**
     * What the change delay hangs on. Anything that gives the user more room has
     * to wait; anything that takes room away happens at once.
     */
    @Test
    fun `giving yourself more room counts as loosening`() {
        assertTrue(isLoosening(BlockMode.Blocked, BlockMode.Allowance(5, Period.DAY)))
        assertTrue(isLoosening(BlockMode.Allowance(5, Period.DAY), BlockMode.Off))
        assertTrue(
            isLoosening(
                BlockMode.Allowance(5, Period.DAY),
                BlockMode.Allowance(30, Period.DAY),
            ),
        )
        assertFalse(isLoosening(BlockMode.Off, BlockMode.Blocked))
        assertFalse(
            isLoosening(
                BlockMode.Allowance(30, Period.DAY),
                BlockMode.Allowance(5, Period.DAY),
            ),
        )
        assertFalse(isLoosening(BlockMode.Blocked, BlockMode.Blocked))
    }
}
