package cat.doorman.app

import cat.doorman.app.model.ScreenSnapshot
import cat.doorman.app.rules.OverlayBounds
import cat.doorman.app.rules.RuleSet
import cat.doorman.app.rules.ScreenEvaluator
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LinkedIn, X and Reddit, against screens captured from the real apps.
 *
 * Each app is pinned the same way: the feed is blocked, and every screen
 * somebody might actually need -- messages above all -- is not. The second
 * half is the half that matters. A blocker that swallows the inbox gets
 * switched off within a day, and then it blocks nothing at all.
 */
class NewAppsRulesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): ScreenSnapshot {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/$name.json")
            ?: error("missing fixture $name")
        return json.decodeFromString(stream.bufferedReader().readText())
    }

    private fun rules(): RuleSet =
        json.decodeFromString(java.io.File("src/main/assets/rules.json").readText())

    private val all: Set<String>
        get() = rules().apps.values.flatMap { it.screens }.map { it.id }.toSet()

    private val defaults: Set<String>
        get() = rules().apps.values.flatMap { it.screens }
            .filter { it.defaultEnabled }.map { it.id }.toSet()

    // ---------------------------------------------------------------- LinkedIn

    @Test
    fun `the linkedin feed is blocked`() {
        val verdict = ScreenEvaluator.evaluate(fixture("linkedin-feed"), rules(), defaults)
        assertTrue(verdict.blocked)
        assertEquals("li_feed", verdict.screenId)
    }

    /**
     * LinkedIn draws My Network from the same server-driven list as the feed,
     * so a rule written on the list id alone blocks both. Which tab is selected
     * is the only thing that separates them -- the same trap Instagram set.
     */
    @Test
    fun `the rest of linkedin is not blocked`() {
        for (screen in listOf(
            "linkedin-network", "linkedin-notifications", "linkedin-messaging",
        )) {
            assertFalse(screen, ScreenEvaluator.evaluate(fixture(screen), rules(), all).blocked)
        }
    }

    @Test
    fun `the linkedin block leaves the tab bar and the search bar reachable`() {
        val app = rules().apps.getValue("com.linkedin.android")
        val band = OverlayBounds.compute(
            snapshot = fixture("linkedin-feed"),
            keepVisibleTopViewIds = app.keepVisibleTopViewIds,
            keepVisibleViewIds = app.keepVisibleViewIds,
        )
        assertTrue("search bar stays above the block", band.topPx > 0)
        assertNotNull("the tab bar stays below it", band.heightPx)
    }

    // ----------------------------------------------------------------------- X

    @Test
    fun `the x home timeline is blocked`() {
        val verdict = ScreenEvaluator.evaluate(fixture("x-home-timeline"), rules(), defaults)
        assertTrue(verdict.blocked)
        assertEquals("x_home_timeline", verdict.screenId)
    }

    @Test
    fun `the rest of x is not blocked`() {
        for (screen in listOf("x-explore", "x-notifications", "x-messages", "x-post")) {
            assertFalse(screen, ScreenEvaluator.evaluate(fixture(screen), rules(), all).blocked)
        }
    }

    /**
     * X names almost nothing. Its tab bar is an untagged Compose row, and the
     * timeline behind it is measured to the full height of the screen, so
     * neither anchor the other apps use exists. The rules fall back to leaving
     * a stated strip at the bottom alone; if that ever stops being configured,
     * the block would cover the way to somebody's messages.
     */
    @Test
    fun `the x block leaves a strip at the bottom for the tab bar`() {
        val app = rules().apps.getValue("com.twitter.android")
        assertTrue("X has no nameable tab bar", app.keepVisibleViewIds.isEmpty())
        assertTrue("so it must state one", app.keepVisibleBottomDp > 0)

        val snapshot = fixture("x-home-timeline")
        val screenHeightPx = 2424
        val band = OverlayBounds.compute(
            snapshot = snapshot,
            keepVisibleTopViewIds = app.keepVisibleTopViewIds,
            keepVisibleViewIds = app.keepVisibleViewIds,
            keepVisibleBottomPx = (app.keepVisibleBottomDp * 2.625).toInt(),
            screenHeightPx = screenHeightPx,
        )
        val bottomOfBlock = band.topPx + (band.heightPx ?: error("the block covers everything"))
        // The tab bar on the captured screen starts at 2213.
        assertTrue("block ends at $bottomOfBlock, tab bar starts at 2213", bottomOfBlock <= 2213)
    }

    // ------------------------------------------------------------------ Reddit

    @Test
    fun `the reddit home feed is blocked`() {
        val verdict = ScreenEvaluator.evaluate(fixture("reddit-home"), rules(), defaults)
        assertTrue(verdict.blocked)
        assertEquals("rd_home_feed", verdict.screenId)
    }

    /**
     * Opening a picture is a finite thing to do; swiping from it to the next
     * post's picture is not. So the player is allowed for a while rather than
     * blocked, the same bargain as a reel someone sent -- and it ships switched
     * off, because Reddit's pager reports a swipe with no distance and no index
     * in it, which leaves a clock as the only way to end the allowance.
     */
    @Test
    fun `the reddit media player is allowed once, not blocked outright`() {
        val verdict = ScreenEvaluator.evaluate(fixture("reddit-media-viewer"), rules(), defaults)
        assertEquals(ScreenEvaluator.Outcome.ALLOW_ONCE, verdict.outcome)
        assertEquals("rd_media_feed", verdict.screenId)
        assertEquals(
            "a swipe Reddit never reports needs a clock behind it",
            120_000L, verdict.allowMillis,
        )
    }

    @Test
    fun `a subreddit and the inbox are not blocked`() {
        for (screen in listOf("reddit-subreddit", "reddit-inbox")) {
            assertFalse(screen, ScreenEvaluator.evaluate(fixture(screen), rules(), all).blocked)
        }
    }

    @Test
    fun `the reddit block leaves the tab bar reachable`() {
        val app = rules().apps.getValue("com.reddit.frontpage")
        val band = OverlayBounds.compute(
            snapshot = fixture("reddit-home"),
            keepVisibleTopViewIds = app.keepVisibleTopViewIds,
            keepVisibleViewIds = app.keepVisibleViewIds,
        )
        assertNotNull("the tab bar stays below the block", band.heightPx)
    }

    // ------------------------------------------------------------------ shared

    @Test
    fun `nothing in the new apps is blocked when everything is switched off`() {
        for (screen in listOf(
            "linkedin-feed", "linkedin-network", "linkedin-notifications", "linkedin-messaging",
            "x-home-timeline", "x-explore", "x-notifications", "x-messages", "x-post",
            "reddit-home", "reddit-media-viewer", "reddit-inbox", "reddit-subreddit",
        )) {
            assertFalse(
                screen,
                ScreenEvaluator.evaluate(fixture(screen), rules(), emptySet()).blocked,
            )
        }
    }
}
