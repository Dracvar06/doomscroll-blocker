package cat.doorman.app

import cat.doorman.app.service.PresenceClock
import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceClockTest {

    private var now = 0L
    private val clock = PresenceClock { now }

    @Test
    fun `time goes to what was in front before the tick`() {
        clock.tick(listOf("app|ig", "screen|ig_stories"))
        now = 30_000
        clock.tick(listOf("app|ig", "screen|ig_reels"))

        assertEquals(mapOf("app|ig" to 30_000L, "screen|ig_stories" to 30_000L), clock.drain())
    }

    @Test
    fun `a long gap is the phone being away, not an hour of Instagram`() {
        clock.tick(listOf("app|ig"))
        now = PresenceClock.MAX_GAP_MS + 1
        clock.tick(listOf("app|ig"))

        assertEquals(emptyMap<String, Long>(), clock.drain())
    }

    @Test
    fun `stopping credits the last stretch and nothing after`() {
        clock.tick(listOf("app|ig"))
        now = 20_000
        clock.stop()
        now = 40_000
        clock.tick(listOf("app|ig"))

        assertEquals(mapOf("app|ig" to 20_000L), clock.drain())
    }
}
