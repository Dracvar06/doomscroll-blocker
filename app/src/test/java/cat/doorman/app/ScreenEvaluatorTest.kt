package cat.doorman.app

import cat.doorman.app.model.ScreenSnapshot
import cat.doorman.app.rules.RuleSet
import cat.doorman.app.rules.ScreenEvaluator
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules are checked against screens actually captured from YouTube 21.32.4
 * on a Pixel 10, not against hand-written trees. A mock would happily confirm
 * whatever the rules already believe.
 *
 * The fixtures were captured on a Catalan device on purpose: if a rule ever
 * starts depending on an English label, these tests fail.
 */
class ScreenEvaluatorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): ScreenSnapshot {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/$name.json")
            ?: error("missing fixture $name")
        return json.decodeFromString(stream.bufferedReader().readText())
    }

    private fun rules(): RuleSet {
        val text = java.io.File("src/main/assets/rules.json").readText()
        return json.decodeFromString(text)
    }

    private val allScreens = setOf(
        "yt_shorts", "yt_home_feed", "yt_subscriptions_feed", "yt_single_short",
        "ig_reels", "ig_feed", "ig_explore", "ig_single_reel",
    )
    private val defaults =
        setOf(
            "yt_shorts", "yt_home_feed", "yt_single_short",
            "ig_reels", "ig_feed", "ig_explore", "ig_single_reel",
        )

    @Test
    fun `shorts is blocked`() {
        val verdict = ScreenEvaluator.evaluate(fixture("youtube-shorts"), rules(), defaults)
        assertTrue(verdict.blocked)
        assertEquals("yt_shorts", verdict.screenId)
    }

    /**
     * The Shorts tab keeps the bottom navigation bar; a Short opened on its own
     * does not. Same discriminator as Instagram, so the two apps behave alike:
     * browsing is blocked, a single item you opened deliberately is not.
     */
    @Test
    fun `the shorts tab is blocked outright, not merely limited`() {
        val verdict = ScreenEvaluator.evaluate(fixture("youtube-shorts"), rules(), defaults)
        assertEquals(ScreenEvaluator.Outcome.BLOCK, verdict.outcome)
        assertEquals("yt_shorts", verdict.screenId)
    }

    @Test
    fun `home feed is blocked`() {
        val verdict = ScreenEvaluator.evaluate(fixture("youtube-home"), rules(), defaults)
        assertTrue(verdict.blocked)
        assertEquals("yt_home_feed", verdict.screenId)
    }

    /**
     * The failure this guards against is real: `results` is the feed list id on
     * home, subscriptions, library AND search, so an obvious home rule built on
     * it blocks all four.
     */
    @Test
    fun `subscriptions and library are not blocked by the home rule`() {
        assertFalse(ScreenEvaluator.evaluate(fixture("youtube-subs"), rules(), defaults).blocked)
        assertFalse(ScreenEvaluator.evaluate(fixture("youtube-you"), rules(), defaults).blocked)
    }

    /**
     * Watching a video is not doomscrolling. This is the screen a link from a
     * friend lands on, so blocking it would break the whole point of the app.
     */
    @Test
    fun `the watch page is never blocked`() {
        assertFalse(ScreenEvaluator.evaluate(fixture("youtube-watch"), rules(), allScreens).blocked)
    }

    @Test
    fun `subscriptions blocks only once the user switches it on`() {
        val off = ScreenEvaluator.evaluate(fixture("youtube-subs"), rules(), defaults)
        assertFalse(off.blocked)
        val on = ScreenEvaluator.evaluate(fixture("youtube-subs"), rules(), allScreens)
        assertTrue(on.blocked)
        assertEquals("yt_subscriptions_feed", on.screenId)
    }

    @Test
    fun `nothing is blocked when the user has switched everything off`() {
        for (screen in listOf(
            "youtube-shorts", "youtube-home", "youtube-subs", "youtube-you", "youtube-watch",
            "instagram-clips", "instagram-feed", "instagram-search", "instagram-profile",
            "instagram-shared-reel", "instagram-dm-thread", "instagram-reel-from-feed",
            "instagram-search-active", "instagram-explore-idle",
        )) {
            assertFalse(screen, ScreenEvaluator.evaluate(fixture(screen), rules(), emptySet()).blocked)
        }
    }

    @Test
    fun `instagram reels and feed are blocked`() {
        val reels = ScreenEvaluator.evaluate(fixture("instagram-clips"), rules(), defaults)
        assertTrue(reels.blocked)
        assertEquals("ig_reels", reels.screenId)

        val feed = ScreenEvaluator.evaluate(fixture("instagram-feed"), rules(), defaults)
        assertTrue(feed.blocked)
        assertEquals("ig_feed", feed.screenId)
    }

    /**
     * The one that matters most. Instagram's ViewPager keeps neighbouring tabs
     * alive, so while you sit on Reels the DM inbox recycler, the feed list and
     * the explore grid are all in the tree at once. Any rule built on a view id
     * merely being *present* blocks the inbox too -- which would break the whole
     * reason this app exists. Selection is the only honest signal.
     */
    @Test
    fun `the profile tab is not blocked even though feed and inbox views exist in the tree`() {
        val profile = fixture("instagram-profile")
        val inboxPresent = profile.nodes.any {
            it.viewIdMatches("inbox_refreshable_thread_list_recyclerview")
        }
        val clipsPresent = profile.nodes.any { it.viewIdMatches("clips_viewer_view_pager") }
        assertTrue("fixture should still contain other tabs' views", inboxPresent || clipsPresent)

        assertFalse(ScreenEvaluator.evaluate(profile, rules(), allScreens).blocked)
    }

    @Test
    fun `instagram explore and search is blocked, and switching it off is obeyed`() {
        val on = ScreenEvaluator.evaluate(fixture("instagram-search"), rules(), defaults)
        assertTrue(on.blocked)
        assertEquals("ig_explore", on.screenId)

        val off = ScreenEvaluator.evaluate(
            fixture("instagram-search"), rules(), defaults - "ig_explore",
        )
        assertFalse(off.blocked)
    }

    /**
     * The case the whole app exists for. A reel a friend sent opens in the same
     * scrollable pager the Reels tab uses, so it must not be blocked outright --
     * but it must not be a free feed either. It is ALLOW_ONCE: watchable, and
     * blocked once a swipe moves past it.
     */
    /**
     * Every way into a single reel gets the same treatment: watchable, and one
     * item only. Matching just the sender views a DM-shared reel carries left
     * every other route open -- opening a reel from a post in the feed gave an
     * unlimited feed, which is the whole thing this app exists to prevent.
     */
    @Test
    fun `every route into a single reel is allowed once, not blocked`() {
        for (route in listOf("instagram-shared-reel", "instagram-reel-from-feed")) {
            val verdict = ScreenEvaluator.evaluate(fixture(route), rules(), defaults)
            assertEquals(route, ScreenEvaluator.Outcome.ALLOW_ONCE, verdict.outcome)
            assertEquals(route, "ig_single_reel", verdict.screenId)
            assertFalse("$route must be watchable", verdict.blocked)
        }
    }

    /**
     * The discriminator: a standalone reel viewer has no tab bar, the Reels tab
     * does. If that ever stops holding, one of these two tests fails rather than
     * the app silently letting a feed through.
     */
    @Test
    fun `a single reel viewer has no tab bar and the reels tab does`() {
        assertFalse(
            "a standalone reel viewer must not carry the tab bar",
            fixture("instagram-reel-from-feed").nodes.any { it.viewIdMatches("clips_tab") },
        )
        assertTrue(
            "the reels tab must carry the tab bar",
            fixture("instagram-clips").nodes.any { it.viewIdMatches("clips_tab") },
        )
    }

    /**
     * Guards the distinction the rule rests on: the shared viewer and the Reels
     * tab share the same pager, and only the sender views and the absent tab bar
     * tell them apart.
     */
    @Test
    fun `the reels tab is still blocked outright, not merely limited`() {
        val verdict = ScreenEvaluator.evaluate(fixture("instagram-clips"), rules(), defaults)
        assertEquals(ScreenEvaluator.Outcome.BLOCK, verdict.outcome)
        assertEquals("ig_reels", verdict.screenId)
    }

    /** Reading and replying to messages must never be touched. */
    @Test
    fun `a conversation is never blocked`() {
        assertFalse(ScreenEvaluator.evaluate(fixture("instagram-dm-thread"), rules(), defaults).blocked)
        assertEquals(
            ScreenEvaluator.Outcome.ALLOW,
            ScreenEvaluator.evaluate(fixture("instagram-dm-thread"), rules(), defaults).outcome,
        )
    }

    /**
     * Searching for a person is not doomscrolling, and it happens on a screen
     * whose tree contains the Reels pager because Instagram keeps neighbouring
     * tabs alive. Matching that pager by presence made the search screen look
     * like a reel viewer -- one scroll through the results and the user would
     * have been blocked mid-search. The pager has to be *visible* to count.
     */
    @Test
    fun `searching is never blocked or limited`() {
        for (screen in listOf("instagram-search-active", "instagram-profile")) {
            val verdict = ScreenEvaluator.evaluate(fixture(screen), rules(), defaults)
            assertEquals(screen, ScreenEvaluator.Outcome.ALLOW, verdict.outcome)
        }
    }

    /** The Explore grid itself must still be blocked, or the toggle does nothing. */
    @Test
    fun `the explore grid is still blocked`() {
        val verdict = ScreenEvaluator.evaluate(fixture("instagram-explore-idle"), rules(), defaults)
        assertEquals(ScreenEvaluator.Outcome.BLOCK, verdict.outcome)
        assertEquals("ig_explore", verdict.screenId)
    }

    @Test
    fun `an unknown app is never blocked`() {
        val foreign = fixture("youtube-shorts").copy(packageName = "com.example.other")
        assertFalse(ScreenEvaluator.evaluate(foreign, rules(), allScreens).blocked)
    }
}
