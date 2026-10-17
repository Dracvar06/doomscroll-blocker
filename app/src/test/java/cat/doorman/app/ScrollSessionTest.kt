package cat.doorman.app

import cat.doorman.app.model.ScreenSnapshot
import cat.doorman.app.rules.RuleSet
import cat.doorman.app.rules.ScreenEvaluator
import cat.doorman.app.service.ScrollBudget
import cat.doorman.app.service.ScrollSession
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one free reel a friend sends you must not become an endless feed.
 *
 * The way round it was this: swipe to the next reel, open the comments before
 * the block lands, close them. With the comments open the screen is one the
 * rules cannot name, the service took that for "the reel was left", and coming
 * back counted as arriving at a new reel -- with a new allowance. The fixtures
 * are what a Pixel 10 really showed with the comments open, which is why they
 * are so small: the sheet hides everything under it from the tree, the reel
 * and the tab bar included, so there is nothing left to anchor a session to.
 */
class ScrollSessionTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): ScreenSnapshot {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/$name.json")
            ?: error("missing fixture $name")
        return json.decodeFromString(stream.bufferedReader().readText())
    }

    private val rules: RuleSet =
        json.decodeFromString(java.io.File("src/main/assets/rules.json").readText())

    private val enabled: Set<String> =
        rules.apps.values.flatMap { it.screens }.map { it.id }.toSet()

    // ---- what the rules make of a comments sheet

    @Test
    fun `comments over a reel are a screen the rules cannot name`() {
        for ((name, fromApp) in listOf(
            "instagram-reel-comments" to false,
            "youtube-short-comments" to true,
        )) {
            val snapshot = fixture(name)
            assertNull(name, ScreenEvaluator.recognise(snapshot, rules, fromApp))
            assertEquals(name, ScreenEvaluator.Outcome.ALLOW, ScreenEvaluator.evaluate(snapshot, rules, enabled, fromApp).outcome)
        }
    }

    // ---- the pure decisions

    private val reel = ScrollSession.Session("com.instagram.android", "ig_single_reel", "com.instagram.android/ig_single_reel")

    private fun ends(screenId: String, pkg: String) =
        rules.appFor(pkg)!!.screens.first { it.id == screenId }.sessionEndViewIds

    @Test
    fun `the first reel starts a session and the same reel does not start another`() {
        assertTrue(ScrollSession.startsFresh(null, "com.instagram.android", "ig_single_reel"))
        assertFalse(ScrollSession.startsFresh(reel, "com.instagram.android", "ig_single_reel"))
        assertTrue(ScrollSession.startsFresh(reel, "com.instagram.android", "ig_reels"))
        assertTrue(ScrollSession.startsFresh(reel, "com.google.android.youtube", "ig_single_reel"))
    }

    @Test
    fun `an unnamed screen leaves the session going`() {
        assertTrue(
            ScrollSession.continues(
                reel, "com.instagram.android", null,
                fixture("instagram-reel-comments"), ends("ig_single_reel", "com.instagram.android"),
            ),
        )
    }

    @Test
    fun `a named screen ends the session`() {
        assertFalse(
            ScrollSession.continues(
                reel, "com.instagram.android", "ig_feed",
                fixture("instagram-feed"), ends("ig_single_reel", "com.instagram.android"),
            ),
        )
    }

    /** The legitimate way to the next reel: back to the conversation, then the next one. */
    @Test
    fun `the conversation ends the session even though the rules do not name it`() {
        val thread = fixture("instagram-dm-thread")
        assertNull(ScreenEvaluator.recognise(thread, rules, false))
        assertFalse(
            ScrollSession.continues(
                reel, "com.instagram.android", null, thread, ends("ig_single_reel", "com.instagram.android"),
            ),
        )
    }

    @Test
    fun `a different app, or nothing readable, does not continue it`() {
        val ends = ends("ig_single_reel", "com.instagram.android")
        assertFalse(ScrollSession.continues(reel, "com.google.android.youtube", null, fixture("youtube-short-comments"), ends))
        assertFalse(ScrollSession.continues(reel, "com.instagram.android", null, null, ends))
        assertFalse(ScrollSession.continues(null, "com.instagram.android", null, fixture("instagram-reel-comments"), ends))
    }

    @Test
    fun `reddit pages with the bottom bar end the session`() {
        val session = ScrollSession.Session("com.reddit.frontpage", "rd_media_feed", "k")
        val ends = ends("rd_media_feed", "com.reddit.frontpage")
        for (page in listOf("reddit-home", "reddit-subreddit", "reddit-inbox")) {
            assertFalse(page, ScrollSession.continues(session, "com.reddit.frontpage", null, fixture(page), ends))
        }
    }

    // ---- the trick itself, end to end through the same pieces the service uses

    /** The service's decisions, replayed: what it does with each screen it is shown. */
    private class Replay(val rules: RuleSet, val enabled: Set<String>, val fixture: (String) -> ScreenSnapshot) {
        val budget = ScrollBudget(viewportHeightPx = { 2424 })
        var session: ScrollSession.Session? = null
        var now = 1_000L

        /** True when the single item is on screen and still allowed. */
        fun show(name: String, fromApp: Boolean): Boolean {
            now += 400
            val snapshot = fixture(name)
            val verdict = ScreenEvaluator.evaluate(snapshot, rules, enabled, fromApp)
            val pkg = snapshot.packageName!!
            if (verdict.outcome == ScreenEvaluator.Outcome.ALLOW_ONCE) {
                val key = "$pkg/${verdict.screenId}"
                if (ScrollSession.startsFresh(session, pkg, verdict.screenId!!)) budget.reset()
                session = ScrollSession.Session(pkg, verdict.screenId!!, key)
                budget.begin(key, verdict.budget, now, verdict.allowMillis)
                return budget.hasBudget(key, now)
            }
            val ending = session?.let { open ->
                rules.appFor(pkg)?.screens?.firstOrNull { it.id == open.screenId }?.sessionEndViewIds
            }.orEmpty()
            val recognised = ScreenEvaluator.recognise(snapshot, rules, fromApp)
            if (!ScrollSession.continues(session, pkg, recognised, snapshot, ending)) session = null
            return false
        }

        /** A swipe to the next reel. */
        fun swipe(pkg: String, screenId: String) {
            now += 200
            budget.onScroll("$pkg/$screenId", 0, 1500, 1, now, 2062)
        }
    }

    private fun replay() = Replay(rules, enabled, ::fixture)

    @Test
    fun `swipe then comments then close does not give the reel back`() {
        val r = replay()
        assertTrue("the reel that was sent plays", r.show("instagram-shared-reel", false))
        r.swipe("com.instagram.android", "ig_single_reel")
        assertFalse("the comments are not the reel", r.show("instagram-reel-comments", false))
        assertFalse("and closing them does not make it free again", r.show("instagram-shared-reel", false))
    }

    @Test
    fun `doing it again and again does not help`() {
        val r = replay()
        r.show("instagram-shared-reel", false)
        r.swipe("com.instagram.android", "ig_single_reel")
        repeat(5) {
            r.show("instagram-reel-comments", false)
            assertFalse(r.show("instagram-shared-reel", false))
            r.swipe("com.instagram.android", "ig_single_reel")
        }
    }

    @Test
    fun `comments on the reel that was sent leave it free`() {
        val r = replay()
        assertTrue(r.show("instagram-shared-reel", false))
        r.show("instagram-reel-comments", false)
        assertTrue("nothing was swiped past", r.show("instagram-shared-reel", false))
    }

    @Test
    fun `the next reel a friend sends is free again after going back to the conversation`() {
        val r = replay()
        assertTrue(r.show("instagram-shared-reel", false))
        r.swipe("com.instagram.android", "ig_single_reel")
        assertFalse(r.show("instagram-shared-reel", false))
        r.show("instagram-dm-thread", false)
        assertTrue("a new reel from the conversation", r.show("instagram-shared-reel", false))
    }

    @Test
    fun `the same on youtube`() {
        val r = replay()
        assertTrue(r.show("youtube-short-from-link", true))
        r.swipe("com.google.android.youtube", "yt_single_short")
        assertFalse(r.show("youtube-short-comments", true))
        assertFalse(r.show("youtube-short-from-link", true))
    }
}
