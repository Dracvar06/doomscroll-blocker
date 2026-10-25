package cat.doorman.app

import cat.doorman.app.model.ScreenSnapshot
import cat.doorman.app.rules.OverlayBounds
import cat.doorman.app.rules.RuleSet
import cat.doorman.app.rules.ScreenEvaluator
import cat.doorman.app.service.CoverMemory
import cat.doorman.app.service.ScrollSession
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RedNote (Xiaohongshu), against screens captured from version 9.51 on a
 * Pixel 10 with all text redacted.
 *
 * RedNote names its views, so it reads like Instagram. Home holds three grids
 * side by side in one pager -- Following, Explore, Nearby -- and each has a
 * pager of its own, so which one is in front is a matter of which is visible
 * while Home is the selected tab. A video opens into a vertical list with the
 * next one already loaded underneath: a feed, held like a single reel.
 *
 * As everywhere, the second half matters more: messages, your profile, search,
 * a post someone opens.
 */
class RedNoteRulesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): ScreenSnapshot {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/$name.json")
            ?: error("missing fixture $name")
        return json.decodeFromString(stream.bufferedReader().readText())
    }

    private val rules: RuleSet =
        json.decodeFromString(java.io.File("src/main/assets/rules.json").readText())

    private val all: Set<String> = rules.apps.values.flatMap { it.screens }.map { it.id }.toSet()

    private val defaults: Set<String> = rules.apps.values.flatMap { it.screens }
        .filter { it.defaultEnabled }.map { it.id }.toSet()

    private val app = rules.apps.getValue("com.xingin.xhs")

    private fun verdict(name: String, enabled: Set<String> = all) =
        ScreenEvaluator.evaluate(fixture(name), rules, enabled)

    @Test
    fun `explore and nearby are blocked from the start`() {
        assertEquals("rn_explore", verdict("rednote-explore", defaults).screenId)
        assertTrue(verdict("rednote-explore", defaults).blocked)
        assertEquals("rn_nearby", verdict("rednote-nearby", defaults).screenId)
        assertTrue(verdict("rednote-nearby", defaults).blocked)
    }

    /**
     * The people you follow, and the shop, are each their own switch and start
     * off: neither is the grid of strangers that keeps you there, and a block
     * nobody asked for is the kind that gets Doorman uninstalled.
     */
    @Test
    fun `following and the market are switches that start off`() {
        assertFalse(verdict("rednote-following", defaults).blocked)
        assertFalse(verdict("rednote-market", defaults).blocked)
        assertEquals("rn_following", verdict("rednote-following").screenId)
        assertTrue(verdict("rednote-following").blocked)
        assertEquals("rn_market", verdict("rednote-market").screenId)
        assertTrue(verdict("rednote-market").blocked)
    }

    /**
     * The three Home grids live in one pager, so all three are in the tree at
     * once. Only the one on screen may count, or Following would be blocked
     * as Explore.
     */
    @Test
    fun `each home tab is only itself`() {
        assertEquals("rn_explore", verdict("rednote-explore").screenId)
        assertEquals("rn_nearby", verdict("rednote-nearby").screenId)
        assertEquals("rn_following", verdict("rednote-following").screenId)
    }

    @Test
    fun `a video you open plays once`() {
        val v = verdict("rednote-video-feed", defaults)
        assertEquals(ScreenEvaluator.Outcome.ALLOW_ONCE, v.outcome)
        assertEquals("rn_video_feed", v.screenId)
    }

    @Test
    fun `the rest of rednote is never blocked`() {
        for (screen in listOf(
            "rednote-messages", "rednote-me", "rednote-profile", "rednote-search",
            "rednote-search-results", "rednote-note", "rednote-note-comments",
            "rednote-video-comments",
        )) {
            val v = verdict(screen)
            assertFalse(screen, v.blocked)
            assertEquals(screen, null, v.screenId)
        }
    }

    // ------------------------------------------------------------ the video

    /**
     * Going back to choose another -- Home, search results, someone's profile
     * -- ends the one video, so the next one you pick plays. The video and its
     * comments must not end it, or swiping and opening the comments would hand
     * back a fresh free video every time.
     */
    @Test
    fun `going back to choose another video ends the one before`() {
        val ends = app.screens.first { it.id == "rn_video_feed" }.sessionEndViewIds
        val session = ScrollSession.Session("com.xingin.xhs", "rn_video_feed", "k")
        for (screen in listOf("rednote-search-results", "rednote-profile", "rednote-me", "rednote-messages")) {
            assertFalse(screen, ScrollSession.continues(session, "com.xingin.xhs", null, fixture(screen), ends))
        }
        assertTrue(ScrollSession.continues(session, "com.xingin.xhs", null, fixture("rednote-video-comments"), ends))
    }

    @Test
    fun `the comments over a video are that video`() {
        var held: CoverMemory.Held? = null
        for ((name, at) in listOf("rednote-video-feed" to 0L, "rednote-video-comments" to 2_000L)) {
            val s = fixture(name)
            val step = CoverMemory.step(
                held, "com.xingin.xhs", ScreenEvaluator.evaluate(s, rules, all),
                ScreenEvaluator.recognise(s, rules), s, app.coverViewIds, at,
            )
            held = step.held
            if (name == "rednote-video-comments") assertEquals("rn_video_feed", step.verdict.screenId)
        }
    }

    /**
     * A post's own page lists its comments too, with many of the same view
     * names as the sheet over a video. The sheet marker must not be among them,
     * or a post opened after a blocked grid would be taken for the grid.
     */
    @Test
    fun `the comment sheet marker is not on a post's own page`() {
        for (screen in listOf("rednote-note", "rednote-note-comments")) {
            val s = fixture(screen)
            assertFalse(screen, app.coverViewIds.any { id -> s.nodes.any { it.viewIdMatches(id) } })
        }
    }

    // ------------------------------------------------------------- the band

    @Test
    fun `the block on a home grid leaves the top tabs, search and the tab bar reachable`() {
        for (screen in listOf("rednote-explore", "rednote-nearby", "rednote-following")) {
            val band = OverlayBounds.compute(
                snapshot = fixture(screen),
                keepVisibleTopViewIds = app.keepVisibleTopViewIds,
                keepVisibleViewIds = app.keepVisibleViewIds,
            )
            // The top tabs end at 273 and search at 278; the tab bar starts at 2243.
            assertTrue("$screen: top ${band.topPx}", band.topPx >= 278)
            assertNotNull(screen, band.heightPx)
            val height = band.heightPx!!
            assertTrue("$screen: bottom ${band.topPx + height}", band.topPx + height <= 2243)
        }
    }

    @Test
    fun `the block on the market leaves its search bar and the tab bar reachable`() {
        val band = OverlayBounds.compute(
            snapshot = fixture("rednote-market"),
            keepVisibleTopViewIds = app.keepVisibleTopViewIds,
            keepVisibleViewIds = app.keepVisibleViewIds,
        )
        assertTrue("top ${band.topPx}", band.topPx >= 298)
        assertTrue(band.topPx + band.heightPx!! <= 2243)
    }
}
