package cat.doorman.app

import cat.doorman.app.model.ScreenSnapshot
import cat.doorman.app.rules.Arrival
import cat.doorman.app.rules.OverlayBounds
import cat.doorman.app.rules.RuleSet
import cat.doorman.app.rules.ScreenEvaluator
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checked against screens captured from TikTok 44.2.4 on a Pixel 10, in
 * Catalan, so a rule that starts leaning on an English label fails here.
 *
 * TikTok is the hardest app Doorman has met. Almost every view id in it is
 * obfuscated -- `oem`, `oee`, `i4o` -- and those names are reassigned on every
 * release, so a rule written against them would not merely stop working, it
 * could start matching some unrelated screen. The rules therefore use only the
 * handful of ids TikTok keeps: the framework's own `android:id/text1`, and
 * `viewpager`, `long_press_layout` and `video_player_progress`.
 */
class TikTokRulesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): ScreenSnapshot {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/$name.json")
            ?: error("missing fixture $name")
        return json.decodeFromString(stream.bufferedReader().readText())
    }

    private fun rules(): RuleSet =
        json.decodeFromString(java.io.File("src/main/assets/rules.json").readText())

    private val defaults: Set<String>
        get() = rules().apps.values.flatMap { it.screens }
            .filter { it.defaultEnabled }.map { it.id }.toSet()

    private fun verdict(
        name: String,
        enabled: Set<String> = defaults,
        fromAnotherApp: Boolean = false,
    ) = ScreenEvaluator.evaluate(fixture(name), rules(), enabled, fromAnotherApp)

    @Test
    fun `the for you feed is blocked`() {
        assertEquals(ScreenEvaluator.Outcome.BLOCK, verdict("tiktok-for-you").outcome)
        assertEquals("tt_feed", verdict("tiktok-for-you").screenId)
    }

    /**
     * The three screens that must survive. Blocking any of these would put
     * Doorman between someone and their messages, which is the failure that
     * teaches people to switch it off.
     */
    @Test
    fun `the inbox, the profile and search are left alone`() {
        assertEquals(ScreenEvaluator.Outcome.ALLOW, verdict("tiktok-inbox").outcome)
        assertEquals(ScreenEvaluator.Outcome.ALLOW, verdict("tiktok-profile").outcome)
        assertEquals(ScreenEvaluator.Outcome.ALLOW, verdict("tiktok-search").outcome)
    }

    /**
     * The finding that shaped these rules. Open a TikTok link a friend sent and
     * TikTok does not show it on its own the way Instagram shows a DM'd reel:
     * it drops you straight into the For You feed, identical in every visible
     * respect. The fixture is a real capture of that arrival, and it is proof
     * that no amount of looking at the screen can separate the two.
     */
    @Test
    fun `a video opened from a link looks exactly like the feed`() {
        val feed = fixture("tiktok-for-you")
        val fromLink = fixture("tiktok-video-from-link")
        val ids = { s: ScreenSnapshot ->
            s.nodes.mapNotNull { it.viewId?.substringAfter("id/") }.toSet()
        }
        // Same chrome: the top tab strip and the tab bar are present in both.
        assertTrue(ids(feed).contains("text1"))
        assertTrue(ids(fromLink).contains("text1"))
        // And so the same rule matches both, when arrival is not considered.
        assertEquals(
            verdict("tiktok-for-you").screenId,
            verdict("tiktok-video-from-link").screenId,
        )
    }

    @Test
    fun `arriving from another app lets the video that was sent play`() {
        val v = verdict("tiktok-video-from-link", fromAnotherApp = true)
        assertEquals(ScreenEvaluator.Outcome.ALLOW_ONCE, v.outcome)
        assertEquals("tt_shared_video", v.screenId)
    }

    @Test
    fun `opening tiktok yourself does not grant that free video`() {
        assertEquals(
            ScreenEvaluator.Outcome.BLOCK,
            verdict("tiktok-for-you", fromAnotherApp = false).outcome,
        )
    }

    @Test
    fun `switching the shared-video allowance off blocks a sent video too`() {
        val v = verdict(
            "tiktok-video-from-link",
            enabled = defaults - "tt_shared_video",
            fromAnotherApp = true,
        )
        assertEquals(ScreenEvaluator.Outcome.BLOCK, v.outcome)
    }

    /**
     * The launcher is not "another app". Without this, opening TikTok from the
     * home screen would be indistinguishable from following a link, and every
     * deliberate visit would come with a free video.
     */
    @Test
    fun `the home screen does not count as arriving from somewhere else`() {
        val launchers = setOf("com.google.android.apps.nexuslauncher")
        assertFalse(
            Arrival.isFromAnotherApp(
                previous = "com.google.android.apps.nexuslauncher",
                current = "com.zhiliaoapp.musically",
                launcherPackages = launchers,
                self = "cat.doorman.app",
            )
        )
        assertTrue(
            Arrival.isFromAnotherApp(
                previous = "com.whatsapp",
                current = "com.zhiliaoapp.musically",
                launcherPackages = launchers,
                self = "cat.doorman.app",
            )
        )
    }

    @Test
    fun `coming back from doorman itself is not an arrival from another app`() {
        assertFalse(
            Arrival.isFromAnotherApp(
                previous = "cat.doorman.app",
                current = "com.zhiliaoapp.musically",
                launcherPackages = emptySet(),
                self = "cat.doorman.app",
            )
        )
    }

    /**
     * The trap test. TikTok's tab bar has no id that survives a release, so the
     * block is anchored to the bottom of the content pager instead. If this
     * ever computes a band that covers the tab bar, a blocked user has no way
     * to reach their messages except turning Doorman off.
     */
    @Test
    fun `the block stops above the tab bar`() {
        val snapshot = fixture("tiktok-for-you")
        val app = rules().apps.getValue("com.zhiliaoapp.musically")
        val band = OverlayBounds.compute(
            snapshot = snapshot,
            keepVisibleTopViewIds = app.keepVisibleTopViewIds,
            keepVisibleViewIds = app.keepVisibleViewIds,
            keepVisibleBelowViewIds = app.keepVisibleBelowViewIds,
        )
        val tabBarTop = snapshot.nodes
            .filter { it.contentDescription == "Inici" }
            .mapNotNull { it.topPx() }
            .minOrNull()
        requireNotNull(tabBarTop) { "fixture has no tab bar to stay clear of" }
        val bandBottom = band.heightPx?.let { band.topPx + it }
        requireNotNull(bandBottom) { "band covers everything below its top, tab bar included" }
        assertTrue(
            "band ends at $bandBottom but the tab bar starts at $tabBarTop",
            bandBottom <= tabBarTop,
        )
    }

    /**
     * TikTok hides search behind an icon in the same top row as the feed tabs,
     * and that icon's own id is obfuscated. The row's labels are not, and they
     * share its line, so anchoring to them keeps search reachable from a blocked
     * feed -- the same thing Instagram's Explore block had to learn.
     */
    @Test
    fun `search stays reachable above a blocked feed`() {
        val snapshot = fixture("tiktok-for-you")
        val app = rules().apps.getValue("com.zhiliaoapp.musically")
        val band = OverlayBounds.compute(
            snapshot = snapshot,
            keepVisibleTopViewIds = app.keepVisibleTopViewIds,
            keepVisibleViewIds = app.keepVisibleViewIds,
            keepVisibleBelowViewIds = app.keepVisibleBelowViewIds,
        )
        val searchBottom = snapshot.nodes
            .first { it.contentDescription == "Cerca" }
            .bottomPx()!!
        assertTrue(
            "band starts at ${band.topPx}, search ends at $searchBottom",
            band.topPx >= searchBottom,
        )
    }

    /**
     * Nesting matters here: TikTok has three views called `viewpager`, and the
     * outermost fills the screen including the tab bar. Anchoring to that one
     * would cover everything.
     */
    @Test
    fun `the innermost content pager is what the block stops at`() {
        val snapshot = fixture("tiktok-for-you")
        assertEquals(2232, OverlayBounds.bottomOf(snapshot, listOf("viewpager")))
    }
}
