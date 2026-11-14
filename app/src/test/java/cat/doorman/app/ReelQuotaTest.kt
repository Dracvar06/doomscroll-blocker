package cat.doorman.app

import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.LimitsCodec
import cat.doorman.app.limits.ReelLedgerCodec
import cat.doorman.app.limits.ReelQuota
import cat.doorman.app.limits.ReelQuotas
import cat.doorman.app.limits.ReelWindow
import cat.doorman.app.limits.isLoosening
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "A reel every quarter of an hour" and "ten an hour", which are the same
 * mechanism with different numbers. The hazards are the edges: the reel that is
 * the last allowed one must play to its end, a swipe past it must close the
 * screen, and the stretch must lift on time and not a minute either side.
 */
class ReelQuotaTest {

    private val minute = 60_000L
    private val oneEveryQuarterHour = ReelQuota(reels = 1, minutes = 15)
    private val tenAnHour = ReelQuota(reels = 10, minutes = 60)

    private fun allowed(entry: ReelQuotas.Entry): ReelWindow =
        (entry as ReelQuotas.Entry.Allowed).window

    // ---- one every fifteen minutes -----------------------------------------

    @Test
    fun `the first reel is free and the swipe past it closes the screen`() {
        val opened = allowed(ReelQuotas.enter(null, oneEveryQuarterHour, 0L))
        assertEquals(ReelWindow(0L, 1), opened)

        val swiped = ReelQuotas.swipe(opened, oneEveryQuarterHour, 2 * minute)
        swiped as ReelQuotas.Swipe.Closed
        assertTrue(swiped.window.over)
        assertEquals(15 * minute, swiped.untilMillis)
    }

    @Test
    fun `the last allowed reel stays watchable until you swipe`() {
        val opened = allowed(ReelQuotas.enter(null, oneEveryQuarterHour, 0L))
        // Nothing has been swiped, so nothing is closed, however long it plays.
        assertNull(ReelQuotas.closedUntil(opened, oneEveryQuarterHour, 10 * minute))
    }

    @Test
    fun `coming back inside the stretch finds the screen closed`() {
        val opened = allowed(ReelQuotas.enter(null, oneEveryQuarterHour, 0L))
        val entry = ReelQuotas.enter(opened, oneEveryQuarterHour, 5 * minute)
        entry as ReelQuotas.Entry.Closed
        assertEquals(15 * minute, entry.untilMillis)
    }

    @Test
    fun `it opens again exactly when the stretch ends`() {
        val spent = ReelWindow(0L, 1, over = true)
        assertEquals(15 * minute, ReelQuotas.closedUntil(spent, oneEveryQuarterHour, 14 * minute + 59_999))
        assertNull(ReelQuotas.closedUntil(spent, oneEveryQuarterHour, 15 * minute))
        // And the next visit starts a fresh stretch with a fresh reel.
        val again = allowed(ReelQuotas.enter(spent, oneEveryQuarterHour, 15 * minute))
        assertEquals(ReelWindow(15 * minute, 1), again)
    }

    // ---- ten an hour -------------------------------------------------------

    @Test
    fun `ten reels are watched and the eleventh swipe closes the screen`() {
        var window = allowed(ReelQuotas.enter(null, tenAnHour, 0L))
        repeat(9) { i ->
            val next = ReelQuotas.swipe(window, tenAnHour, (i + 1) * minute)
            window = (next as ReelQuotas.Swipe.Next).window
        }
        assertEquals(10, window.count)
        assertEquals(0, ReelQuotas.remaining(window, tenAnHour, 10 * minute))

        val last = ReelQuotas.swipe(window, tenAnHour, 11 * minute)
        assertTrue(last is ReelQuotas.Swipe.Closed)
        assertEquals(0, ReelQuotas.remaining((last as ReelQuotas.Swipe.Closed).window, tenAnHour, 12 * minute))
    }

    @Test
    fun `reels left are told as they are used`() {
        val opened = allowed(ReelQuotas.enter(null, tenAnHour, 0L))
        assertEquals(9, ReelQuotas.remaining(opened, tenAnHour, minute))
        assertEquals(10, ReelQuotas.remaining(null, tenAnHour, minute))
    }

    // ---- the stretch running out while somebody watches --------------------

    @Test
    fun `a swipe after the stretch ended begins a new one`() {
        val old = ReelWindow(0L, 10)
        val swiped = ReelQuotas.swipe(old, tenAnHour, 61 * minute)
        assertEquals(ReelQuotas.Swipe.Next(ReelWindow(61 * minute, 1)), swiped)
    }

    @Test
    fun `a clock set backwards does not hold a stretch open`() {
        val future = ReelWindow(1_000 * minute, 10, over = true)
        assertNull(ReelQuotas.current(future, tenAnHour, 0L))
    }

    // ---- looser and tighter ------------------------------------------------

    @Test
    fun `more reels in the same time is looser, fewer is not`() {
        assertTrue(ReelQuotas.isLooser(ReelQuota(5, 60), ReelQuota(6, 60)))
        assertFalse(ReelQuotas.isLooser(ReelQuota(5, 60), ReelQuota(4, 60)))
    }

    @Test
    fun `the same count over less time is looser`() {
        assertTrue(ReelQuotas.isLooser(ReelQuota(1, 15), ReelQuota(1, 10)))
        assertFalse(ReelQuotas.isLooser(ReelQuota(1, 15), ReelQuota(1, 30)))
    }

    @Test
    fun `the same rate written differently is not looser`() {
        assertFalse(ReelQuotas.isLooser(ReelQuota(1, 15), ReelQuota(2, 30)))
    }

    @Test
    fun `dropping a quota is looser and adding one is not`() {
        assertTrue(ReelQuotas.isLooser(oneEveryQuarterHour, null))
        assertFalse(ReelQuotas.isLooser(null, oneEveryQuarterHour))
    }

    @Test
    fun `changing a quota goes through the change delay only when it loosens`() {
        val held = Limits(reels = oneEveryQuarterHour)
        assertTrue(isLoosening(held, Limits.OFF))
        assertTrue(isLoosening(held, Limits(reels = ReelQuota(2, 15))))
        assertFalse(isLoosening(held, Limits(reels = ReelQuota(1, 30))))
        assertFalse(isLoosening(Limits.OFF, held))
        // Held outright is stricter than any quota.
        assertFalse(isLoosening(held, Limits.BLOCKED))
    }

    // ---- storage -----------------------------------------------------------

    @Test
    fun `a quota survives being written and read back`() {
        val limits = Limits(reels = ReelQuota(3, 45))
        assertEquals(limits, LimitsCodec.decode(LimitsCodec.encode(limits)))
        assertEquals(
            Limits.OFF,
            LimitsCodec.decode(LimitsCodec.encode(Limits.OFF)),
        )
    }

    @Test
    fun `limits stored before quotas existed still read as they were`() {
        assertEquals(
            Limits.BLOCKED,
            LimitsCodec.decode("""{"blocked":true}"""),
        )
    }

    @Test
    fun `a quota that makes no sense is dropped rather than guessed`() {
        assertEquals(
            Limits.OFF,
            LimitsCodec.decode("""{"reels":{"n":0,"m":15}}"""),
        )
    }

    @Test
    fun `the books survive being written and read back`() {
        val ledger = mapOf(
            "ig_reels" to ReelWindow(1_000L, 3),
            "yt_shorts" to ReelWindow(2_000L, 1, over = true),
        )
        assertEquals(ledger, ReelLedgerCodec.decode(ReelLedgerCodec.encode(ledger)))
    }

    @Test
    fun `unreadable books are an empty book`() {
        assertEquals(emptyMap<String, ReelWindow>(), ReelLedgerCodec.decode("not json"))
        assertEquals(emptyMap<String, ReelWindow>(), ReelLedgerCodec.decode(null))
    }
}
