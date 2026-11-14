package cat.doorman.app

import cat.doorman.app.limits.Allowances
import cat.doorman.app.limits.Allowances.Spent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/** The ledger is added to, never replaced, and memory never takes time back. */
class LedgerWriteTest {

    private val now = LocalDateTime.of(2026, 10, 17, 12, 0)
    private val key = "app|HOUR"
    private val period = "2026-10-17T12"

    private fun millis(ledger: Map<String, Spent>) = ledger.getValue(key).millis

    @Test
    fun `a stale emission does not lose time`() {
        var disk = emptyMap<String, Spent>()
        var memory = emptyMap<String, Spent>()

        // Flush A, then the emission it causes is held up while flush B lands.
        disk = Allowances.applyDeltas(disk, mapOf(key to 10_000L), now)
        memory = Allowances.withPending(memory, mapOf(key to 10_000L), now)
        val staleEmission = disk
        disk = Allowances.applyDeltas(disk, mapOf(key to 4_000L), now)
        memory = Allowances.withPending(memory, mapOf(key to 4_000L), now)

        memory = Allowances.merge(memory, staleEmission, now)
        assertEquals(14_000L, millis(memory))

        // A third flush writes only its own delta, so the disk total is the sum.
        disk = Allowances.applyDeltas(disk, mapOf(key to 6_000L), now)
        assertEquals(20_000L, millis(disk))
        memory = Allowances.merge(memory, disk, now)
        assertEquals(20_000L, millis(memory))
    }

    @Test
    fun `a write adds to what the disk holds now rather than what was seen before`() {
        val disk = mapOf(key to Spent(period, 30_000L))

        assertEquals(35_000L, millis(Allowances.applyDeltas(disk, mapOf(key to 5_000L), now)))
    }

    @Test
    fun `the current period wins over an old one on either side`() {
        val old = Spent("2026-10-17T11", 50_000L)
        val current = Spent(period, 3_000L)

        assertEquals(current, Allowances.merge(mapOf(key to old), mapOf(key to current), now)[key])
        assertEquals(current, Allowances.merge(mapOf(key to current), mapOf(key to old), now)[key])
    }

    @Test
    fun `a key only the disk knows is taken`() {
        val merged = Allowances.merge(emptyMap(), mapOf(key to Spent(period, 7_000L)), now)

        assertEquals(7_000L, millis(merged))
    }
}
