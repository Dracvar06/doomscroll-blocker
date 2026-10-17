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

        fun see(name: String, fromApp: Boolean = false): ScreenEvaluator.Verdict {
            val snapshot = fixture(name)
            val pkg = snapshot.packageName!!
            val verdict = ScreenEvaluator.evaluate(snapshot, rules, enabled, fromApp)
            val recognised = ScreenEvaluator.recognise(snapshot, rules, fromApp)
            val step = CoverMemory.step(
                held, pkg, verdict, recognised, snapshot, rules.appFor(pkg)?.coverViewIds.orEmpty(),
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
}
