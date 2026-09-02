package cat.doorman.app

import cat.doorman.app.data.ScreenPreferences
import cat.doorman.app.limits.BlockMode
import cat.doorman.app.limits.Period
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What happens to somebody's settings when Doorman updates. The bug these
 * guard against was real: a flat "has been configured" flag meant anyone who
 * had ever touched a switch never received support for a new app at all.
 */
class ScreenPreferencesTest {

    private val withInstagram = mapOf(
        "yt_shorts" to true,
        "yt_subscriptions_feed" to false,
        "ig_reels" to true,
    )

    @Test
    fun `a screen nobody has ruled on takes the default it ships with`() {
        val modes = ScreenPreferences.effectiveModes(
            withInstagram, decided = emptySet(), enabled = emptySet(), storedModes = emptyMap(),
        )
        assertEquals(BlockMode.Blocked, modes["yt_shorts"])
        assertEquals(BlockMode.Off, modes["yt_subscriptions_feed"])
    }

    @Test
    fun `a screen the user switched off stays off`() {
        val modes = ScreenPreferences.effectiveModes(
            withInstagram,
            decided = setOf("yt_shorts"),
            enabled = emptySet(),
            storedModes = emptyMap(),
        )
        assertEquals(BlockMode.Off, modes["yt_shorts"])
    }

    /** The update bug: a new app must arrive switched on as it ships. */
    @Test
    fun `an app added by an update arrives at its default`() {
        val modes = ScreenPreferences.effectiveModes(
            withInstagram,
            decided = setOf("yt_shorts", "yt_subscriptions_feed"),
            enabled = setOf("yt_shorts"),
            storedModes = emptyMap(),
        )
        assertEquals(BlockMode.Blocked, modes["ig_reels"])
    }

    /**
     * The upgrade into allowances. An install from before modes existed has
     * only a set of switched-on screens, and those have to keep meaning
     * blocked -- an update that quietly unheld them would be a betrayal.
     */
    @Test
    fun `switches from before allowances existed still mean blocked`() {
        val modes = ScreenPreferences.effectiveModes(
            withInstagram,
            decided = setOf("yt_shorts", "yt_subscriptions_feed"),
            enabled = setOf("yt_shorts"),
            storedModes = emptyMap(),
        )
        assertEquals(BlockMode.Blocked, modes["yt_shorts"])
        assertEquals(BlockMode.Off, modes["yt_subscriptions_feed"])
    }

    @Test
    fun `a chosen mode wins over an older on-off decision`() {
        val allowance = BlockMode.Allowance(5, Period.DAY)
        val modes = ScreenPreferences.effectiveModes(
            withInstagram,
            decided = setOf("yt_shorts"),
            enabled = setOf("yt_shorts"),
            storedModes = mapOf("yt_shorts" to allowance),
        )
        assertEquals(allowance, modes["yt_shorts"])
    }

    /**
     * A screen with an allowance still has to be recognised: the allowance is
     * spent by looking at it, so the evaluator has to know when it is in front.
     */
    @Test
    fun `only switched-off screens leave the running`() {
        val modes = mapOf(
            "a" to BlockMode.Blocked,
            "b" to BlockMode.Allowance(5, Period.HOUR),
            "c" to BlockMode.Off,
        )
        assertEquals(setOf("a", "b"), ScreenPreferences.activeScreenIds(modes))
    }
}
