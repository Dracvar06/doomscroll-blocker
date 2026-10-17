package cat.doorman.app

import cat.doorman.app.model.ScreenSnapshot
import cat.doorman.app.rules.RuleSet
import cat.doorman.app.rules.ScreenEvaluator
import cat.doorman.app.service.CoverMemory
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A comments sheet over a blocked screen is that screen.
 *
 * With the comments open the reel is gone from the tree, so Doorman saw nothing
 * and dropped the block: the reel kept playing above the sheet, with sound. The
 * fixtures are what a Pixel 10 showed, which is why the sheets are so small.
 *
 * The cases that must stay open matter as much as the ones that must close.
 * Blocking the wrong screen is the failure that kills the app, so the memory is
 * narrow: a known sheet, over a screen that was blocked, in the same app.
 */
class CoverMemoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): ScreenSnapshot {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/$name.json")
            ?: error("missing fixture $name")
        return json.decodeFromString(stream.bufferedReader().readText())
    }

    private val rules: RuleSet =
        json.decodeFromString(java.io.File("src/main/assets/rules.json").readText())

    private val all: Set<String> = rules.apps.values.flatMap { it.screens }.map { it.id }.toSet()

    /** What the service does with each screen it is shown. */
    private class Replay(val rules: RuleSet, val enabled: Set<String>, val fixture: (String) -> ScreenSnapshot) {
        var held: CoverMemory.Held? = null
        var now = 0L

        /**
         * Each screen [after] the one before. Two seconds by default, the pace
         * of a person tapping from one screen to the next; the tests about
         * frames in between pass their own, much shorter, gaps.
         */
        fun see(name: String, fromApp: Boolean = false, after: Long = HUMAN_STEP_MS): ScreenEvaluator.Verdict =
            see(fixture(name), fromApp, after)

        fun see(snapshot: ScreenSnapshot, fromApp: Boolean = false, after: Long = HUMAN_STEP_MS): ScreenEvaluator.Verdict {
            now += after
            val pkg = snapshot.packageName!!
            val verdict = ScreenEvaluator.evaluate(snapshot, rules, enabled, fromApp)
            val recognised = ScreenEvaluator.recognise(snapshot, rules, fromApp)
            val step = CoverMemory.step(
                held, pkg, verdict, recognised, snapshot, rules.appFor(pkg)?.coverViewIds.orEmpty(), now,
                justArrivedFromAnotherApp = fromApp,
            )
            held = step.held
            return step.verdict
        }
    }

    private fun replay(enabled: Set<String> = all) = Replay(rules, enabled, ::fixture)

    @Test
    fun `comments over the blocked reels are the reels`() {
        val r = replay()
        assertEquals("ig_reels", r.see("instagram-clips").screenId)
        val sheet = r.see("instagram-reel-comments")
        assertTrue(sheet.blocked)
        assertEquals("ig_reels", sheet.screenId)
    }

    @Test
    fun `and the same on youtube shorts`() {
        val r = replay()
        assertEquals("yt_shorts", r.see("youtube-shorts").screenId)
        val sheet = r.see("youtube-short-comments")
        assertTrue(sheet.blocked)
        assertEquals("yt_shorts", sheet.screenId)
    }

    @Test
    fun `closing the sheet is the reel again`() {
        val r = replay()
        r.see("instagram-clips")
        r.see("instagram-reel-comments")
        assertEquals("ig_reels", r.see("instagram-clips").screenId)
        assertTrue(r.see("instagram-reel-comments").blocked)
    }

    // ---- what must stay open

    @Test
    fun `comments with no blocked screen before them are left alone`() {
        val r = replay()
        assertFalse(r.see("instagram-reel-comments").blocked)
        assertFalse(r.see("youtube-short-comments").blocked)
    }

    /** The home feed, held by nobody: its comments are nobody's business. */
    @Test
    fun `comments over a screen that is not held are left alone`() {
        val r = replay(enabled = all - "ig_feed")
        assertEquals(ScreenEvaluator.Outcome.ALLOW, r.see("instagram-feed").outcome)
        assertFalse(r.see("instagram-reel-comments").blocked)
    }

    @Test
    fun `the conversation after a blocked screen is not blocked and forgets it`() {
        val r = replay()
        r.see("instagram-clips")
        assertFalse(r.see("instagram-dm-thread").blocked)
        assertFalse("the sheet no longer has a blocked screen under it", r.see("instagram-reel-comments").blocked)
    }

    @Test
    fun `a profile after a blocked screen is not blocked`() {
        val r = replay()
        r.see("instagram-clips")
        assertFalse(r.see("instagram-profile").blocked)
        assertNull(r.held)
    }

    @Test
    fun `a sheet in another app is not the blocked screen of this one`() {
        val r = replay()
        r.see("instagram-clips")
        assertFalse(r.see("youtube-short-comments").blocked)
    }

    @Test
    fun `an unnamed screen that is no known sheet ends the memory`() {
        val r = replay()
        r.see("instagram-clips")
        // The story composer is named (recognised) and held by no one here, so it ends it too.
        assertFalse(r.see("instagram-story-composer").blocked)
        assertFalse(r.see("instagram-reel-comments").blocked)
    }

    @Test
    fun `apps without sheet markers behave as before`() {
        val r = replay()
        assertTrue(r.see("tiktok-for-you").blocked)
        // TikTok has no markers, so nothing can be held over by a sheet there.
        assertEquals(emptyList<String>(), rules.appFor("com.zhiliaoapp.musically")!!.coverViewIds)
    }

    /** A sheet halfway up: a few views of Instagram's, none of the sheet's own ids yet. */
    private val sheetSlidingUp = ScreenSnapshot(packageName = "com.instagram.android")

    @Test
    fun `comments over a single reel are that reel, for its budget to decide`() {
        // His report: swipe past a single reel, the block lands, open the
        // comments, and the next reel played above them. Whether the reel
        // still had its one allowance is the budget's question; this hands
        // the service the reel back so it can ask.
        val r = replay()
        assertEquals("ig_single_reel", r.see("instagram-reel-from-feed").screenId)
        val under = r.see("instagram-reel-comments")
        assertEquals(ScreenEvaluator.Outcome.ALLOW_ONCE, under.outcome)
        assertEquals("ig_single_reel", under.screenId)
    }

    @Test
    fun `a frame in between does not make it forget`() {
        val r = replay()
        r.see("instagram-clips")
        r.see(sheetSlidingUp, after = 80)
        assertTrue(r.see("instagram-reel-comments", after = 320).blocked)
    }

    @Test
    fun `but the frame in between is not blocked itself`() {
        // Kept in mind, never acted on: an unnamed screen after a blocked one
        // may be the inbox, and blocking that is the failure that matters.
        val r = replay()
        r.see("instagram-clips")
        assertFalse(r.see(sheetSlidingUp, after = 80).blocked)
    }

    @Test
    fun `the moment in between runs out`() {
        val r = replay()
        r.see("instagram-clips")
        r.see(sheetSlidingUp, after = CoverMemory.TRANSITION_GRACE_MS + 1)
        assertFalse(r.see("instagram-reel-comments", after = 100).blocked)
    }

    @Test
    fun `the sheet keeps the memory fresh however long it is open`() {
        val r = replay()
        r.see("instagram-clips")
        repeat(10) { assertTrue(r.see("instagram-reel-comments", after = 1_000).blocked) }
        r.see(sheetSlidingUp, after = 500)
        assertTrue(r.see("instagram-reel-comments", after = 500).blocked)
    }

    @Test
    fun `the reels tab with its tab bar hidden is still the reels tab`() {
        // His report: on the blocked Reels tab, tapping the comments hides the
        // tab bar for a frame before the sheet, and a reel with no tab bar is
        // what a single reel looks like. It got a fresh allowance and the
        // comments stayed open with the Reels tab playing above them.
        val r = replay()
        r.see("instagram-clips")
        val noTabBar = r.see("instagram-reel-from-feed", after = 200)
        assertTrue(noTabBar.blocked)
        assertEquals("ig_reels", noTabBar.screenId)
        r.see(sheetSlidingUp, after = 80)
        val sheet = r.see("instagram-reel-comments", after = 150)
        assertTrue(sheet.blocked)
        assertEquals("ig_reels", sheet.screenId)
    }

    @Test
    fun `and so it is after a trip to the home screen`() {
        // Where he did it: home, back into Instagram, and the comments tapped
        // in the moment before the block was drawn again.
        val r = replay()
        r.see("instagram-clips")
        assertTrue(r.see("instagram-reel-from-feed", after = 4_000).blocked)
    }

    @Test
    fun `a reel opened from a profile after the reels tab is the one you chose`() {
        val r = replay()
        r.see("instagram-clips")
        assertFalse(r.see("instagram-profile").blocked)
        val reel = r.see("instagram-reel-from-feed")
        assertEquals(ScreenEvaluator.Outcome.ALLOW_ONCE, reel.outcome)
        assertEquals("ig_single_reel", reel.screenId)
    }

    @Test
    fun `so is one opened from a conversation`() {
        val r = replay()
        r.see("instagram-clips")
        assertFalse(r.see("instagram-dm-thread").blocked)
        assertEquals(ScreenEvaluator.Outcome.ALLOW_ONCE, r.see("instagram-shared-reel").outcome)
    }

    @Test
    fun `a link from another app opens its reel even right after the reels tab`() {
        val r = replay()
        r.see("instagram-clips")
        assertEquals(ScreenEvaluator.Outcome.ALLOW_ONCE, r.see("instagram-shared-reel", fromApp = true).outcome)
    }

    @Test
    fun `a single reel after a screen that is not blocked is the single reel`() {
        val r = replay(enabled = all - "ig_reels" - "ig_reel_viewer")
        r.see("instagram-clips")
        assertEquals(ScreenEvaluator.Outcome.ALLOW_ONCE, r.see("instagram-reel-from-feed", after = 200).outcome)
    }

    @Test
    fun `time away from the app is not time in between`() {
        // On his phone: the blocked Reels tab, home for a few seconds, back,
        // and the comments tapped at once. The first frame back is neither the
        // tab nor the sheet, and it used to end the memory because the moment
        // for such frames had run out while he was on the home screen.
        val r = replay()
        r.see("instagram-clips")
        r.now += 4_000
        r.held = CoverMemory.returned(r.held, "com.instagram.android", r.now)
        r.see(sheetSlidingUp, after = 370)
        assertTrue(r.see("instagram-reel-comments", after = 490).blocked)
    }

    @Test
    fun `coming back to another app leaves the memory as it was`() {
        val r = replay()
        r.see("instagram-clips")
        val back = CoverMemory.returned(r.held, "com.google.android.youtube", r.now + 4_000)
        assertEquals(r.held, back)
    }

    private companion object {
        const val HUMAN_STEP_MS = 2_000L
    }
}
