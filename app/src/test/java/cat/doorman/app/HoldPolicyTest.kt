package cat.doorman.app

import cat.doorman.app.service.Foreground
import cat.doorman.app.service.HoldPolicy
import cat.doorman.app.service.WindowFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A block stays up while a swipe home is being held, and never longer than
 * that: trapping somebody behind a block is the one outcome to avoid.
 */
class HoldPolicyTest {

    private val yt = "com.google.android.youtube"

    @Test
    fun `a held swipe keeps the block while the app's window is listed`() {
        assertTrue(HoldPolicy.keepBlock(yt, setOf(yt, "launcher"), true, 300))
    }

    @Test
    fun `a real Home takes the block down once the window is gone`() {
        assertFalse(HoldPolicy.keepBlock(yt, setOf("launcher"), true, 300))
    }

    @Test
    fun `nothing was blocked means nothing to keep`() {
        assertFalse(HoldPolicy.keepBlock(null, setOf(yt), true, 0))
    }

    @Test
    fun `the screen being off ends it`() {
        assertFalse(HoldPolicy.keepBlock(yt, setOf(yt), false, 0))
    }

    /** Recents leaves the window listed too, so the hold has a limit. */
    @Test
    fun `it never holds for long`() {
        assertTrue(HoldPolicy.keepBlock(yt, setOf(yt), true, HoldPolicy.MAX_HOLD_MS - 1))
        assertFalse(HoldPolicy.keepBlock(yt, setOf(yt), true, HoldPolicy.MAX_HOLD_MS))
        assertFalse(HoldPolicy.keepBlock(yt, setOf(yt), true, 60_000))
    }

    // ---- what the window list reports as present

    private fun win(pkg: String, active: Boolean = false, pip: Boolean = false) =
        WindowFact(pkg, true, active, active, pip)

    private val ignore = setOf("com.android.systemui", "cat.doorman.app")

    @Test
    fun `an app whose window is listed but not active is present`() {
        val result = Foreground.resolve(listOf(win(yt), win("launcher", active = true)), ignore)
        assertEquals(setOf(yt, "launcher"), result.present)
        assertEquals("launcher", result.appPackage)
    }

    @Test
    fun `a floating window is not present, it has its own handling`() {
        val result = Foreground.resolve(listOf(win(yt, pip = true), win("launcher", active = true)), ignore)
        assertEquals(setOf("launcher"), result.present)
    }

    @Test
    fun `the shade and Doorman are never present`() {
        val result = Foreground.resolve(
            listOf(win("com.android.systemui", active = true), win("cat.doorman.app"), win(yt)),
            ignore,
        )
        assertEquals(setOf(yt), result.present)
    }
}
