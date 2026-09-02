package cat.doorman.app

import cat.doorman.app.model.ScreenSnapshot
import cat.doorman.app.rules.OverlayBounds
import cat.doorman.app.rules.RuleSet
import cat.doorman.app.rules.ScreenEvaluator
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checked against screens captured from Snapchat 13.79 on a Pixel 10.
 *
 * Snapchat obfuscates harder than TikTok in one way and softer in another: most
 * of its views report the literal id `0_resource_name_obfuscated`, so their
 * names are gone rather than shortened -- and matching on that string would
 * match nearly every view in the app. But the views that matter kept real
 * names: the nav bar, the Discover cards, the friends' cards, the chat rows.
 * Those are what the rules use.
 *
 * The chat, map and memories fixtures have had their text and content
 * descriptions removed. They were captures of a real account and carried
 * friends' names and conversation history; the rules only ever read view ids,
 * so nothing about the test is weakened by taking the words out.
 */
class SnapchatRulesTest {

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

    private fun verdict(name: String, enabled: Set<String> = defaults) =
        ScreenEvaluator.evaluate(fixture(name), rules(), enabled)

    @Test
    fun `spotlight is blocked`() {
        val v = verdict("snapchat-spotlight")
        assertEquals(ScreenEvaluator.Outcome.BLOCK, v.outcome)
        assertEquals("sc_spotlight", v.screenId)
    }

    @Test
    fun `the discover feed is blocked`() {
        val v = verdict("snapchat-stories")
        assertEquals(ScreenEvaluator.Outcome.BLOCK, v.outcome)
        assertEquals("sc_stories", v.screenId)
    }

    /**
     * Snapchat is a messaging app before it is anything else. Blocking any of
     * these would be the worst false positive Doorman could produce.
     */
    @Test
    fun `chat, camera, map and memories are left alone`() {
        assertEquals(ScreenEvaluator.Outcome.ALLOW, verdict("snapchat-chat").outcome)
        assertEquals(ScreenEvaluator.Outcome.ALLOW, verdict("snapchat-camera").outcome)
        assertEquals(ScreenEvaluator.Outcome.ALLOW, verdict("snapchat-map").outcome)
        assertEquals(ScreenEvaluator.Outcome.ALLOW, verdict("snapchat-memories").outcome)
    }

    /**
     * The Map carries an id called `visual-places-pivot-Favorites`, and
     * Spotlight is recognised by one called `favorite`. Ids are compared whole,
     * not by substring, and this is the test that says so -- a looser match
     * would block the map someone uses to find their friends.
     */
    @Test
    fun `the map's Favorites pivot is not mistaken for spotlight`() {
        val map = fixture("snapchat-map")
        assertTrue(
            map.nodes.any { it.viewId?.contains("Favorites") == true },
        )
        assertEquals(ScreenEvaluator.Outcome.ALLOW, verdict("snapchat-map").outcome)
    }

    private fun band(enabled: Set<String>): OverlayBounds.Band {
        val snapshot = fixture("snapchat-stories")
        val app = rules().apps.getValue("com.snapchat.android")
        val rule = app.screens.first { it.id == "sc_stories" }
        return OverlayBounds.compute(
            snapshot = snapshot,
            keepVisibleTopViewIds = OverlayBounds.topAnchorsFor(
                rule = rule,
                appLevelAnchors = app.keepVisibleTopViewIds,
                enabledScreenIds = enabled,
            ),
            keepVisibleViewIds = app.keepVisibleViewIds,
            keepVisibleBelowViewIds = app.keepVisibleBelowViewIds,
        )
    }

    private fun bottomOfFriendsRow(): Int =
        fixture("snapchat-stories").nodes
            .filter { it.viewIdMatches("friend_card_frame") }
            .mapNotNull { it.bottomPx() }
            .max()

    @Test
    fun `the friends' story row stays reachable while Discover is covered`() {
        assertEquals(bottomOfFriendsRow(), band(setOf("sc_stories")).topPx)
    }

    @Test
    fun `switching the friends' row on covers it too`() {
        assertTrue(band(setOf("sc_stories", "sc_friends_story_row")).topPx < bottomOfFriendsRow())
    }

    /**
     * Covering the friends' row is not a request to cover the search icon above
     * it. Withdrawing the row's own anchor falls back to the app's, so search
     * survives either way -- the same principle that kept Instagram's search bar
     * when its Explore grid was blocked.
     */
    @Test
    fun `covering the friends' row still leaves search reachable`() {
        val searchBottom = fixture("snapchat-stories").nodes
            .first { it.viewIdMatches("hova_header_search_icon") }
            .bottomPx()!!
        assertEquals(searchBottom, band(setOf("sc_stories", "sc_friends_story_row")).topPx)
    }

    /** The nav bar is how a blocked user reaches their chats. */
    @Test
    fun `the block stops above the nav bar`() {
        val navTop = fixture("snapchat-stories").nodes
            .filter { it.viewIdMatches("ngs_chat_icon_container") }
            .mapNotNull { it.topPx() }
            .min()
        val b = band(setOf("sc_stories"))
        val bottom = b.heightPx?.let { b.topPx + it }
        requireNotNull(bottom) { "band covers everything below its top, nav bar included" }
        assertTrue("band ends at $bottom, nav bar starts at $navTop", bottom <= navTop)
    }
}
