package cat.doorman.app

import cat.doorman.app.data.ScreenPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Upgrades are where a settings model quietly breaks, so the awkward cases are
 * pinned down here rather than discovered by users after a release.
 */
class ScreenPreferencesTest {

    private val youtubeOnly = mapOf("yt_shorts" to true, "yt_home_feed" to true)
    private val withInstagram = youtubeOnly + mapOf("ig_reels" to true, "ig_explore" to false)

    @Test
    fun `a fresh install gets the shipped defaults`() {
        val enabled = ScreenPreferences.effectiveEnabled(withInstagram, decided = emptySet(), enabled = emptySet())
        assertEquals(setOf("yt_shorts", "yt_home_feed", "ig_reels"), enabled)
    }

    /**
     * The bug this exists to prevent: someone flicks one switch, then an update
     * adds Instagram. Treating "has configured anything" as "use their set and
     * ignore defaults" means Instagram support arrives switched off forever, and
     * nothing tells them.
     */
    @Test
    fun `screens added by an update arrive at their default without undoing choices`() {
        // The user deliberately turned the YouTube home feed off, long ago.
        val enabled = ScreenPreferences.effectiveEnabled(
            screenDefaults = withInstagram,
            decided = setOf("yt_shorts", "yt_home_feed"),
            enabled = setOf("yt_shorts"),
        )
        assertEquals(setOf("yt_shorts", "ig_reels"), enabled)
    }

    @Test
    fun `switching everything off is obeyed and is not mistaken for a fresh install`() {
        val enabled = ScreenPreferences.effectiveEnabled(
            screenDefaults = withInstagram,
            decided = withInstagram.keys,
            enabled = emptySet(),
        )
        assertEquals(emptySet<String>(), enabled)
    }

    @Test
    fun `a screen switched on against its default stays on`() {
        val enabled = ScreenPreferences.effectiveEnabled(
            screenDefaults = withInstagram,
            decided = setOf("ig_explore"),
            enabled = setOf("ig_explore"),
        )
        assertEquals(setOf("yt_shorts", "yt_home_feed", "ig_reels", "ig_explore"), enabled)
    }

    @Test
    fun `ids no longer shipped are dropped rather than lingering`() {
        val enabled = ScreenPreferences.effectiveEnabled(
            screenDefaults = youtubeOnly,
            decided = setOf("yt_shorts", "removed_screen"),
            enabled = setOf("yt_shorts", "removed_screen"),
        )
        assertEquals(setOf("yt_shorts", "yt_home_feed"), enabled)
    }
}
