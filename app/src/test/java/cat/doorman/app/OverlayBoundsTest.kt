package cat.doorman.app

import cat.doorman.app.model.ScreenSnapshot
import cat.doorman.app.rules.OverlayBounds
import cat.doorman.app.rules.RuleSet
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A block that covers the whole screen corners the user, and a cornered user
 * switches the app off. Two controls have to survive every block: the tab bar,
 * which is the way to your messages, and the search bar, which is how you look
 * somebody up.
 */
class OverlayBoundsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): ScreenSnapshot =
        json.decodeFromString(
            javaClass.classLoader!!.getResourceAsStream("fixtures/$name.json")!!
                .bufferedReader().readText()
        )

    private fun rules(): RuleSet =
        json.decodeFromString(java.io.File("src/main/assets/rules.json").readText())

    private fun bandFor(fixtureName: String, pkg: String): OverlayBounds.Band {
        val app = rules().apps[pkg]!!
        return OverlayBounds.compute(
            fixture(fixtureName), app.keepVisibleTopViewIds, app.keepVisibleViewIds,
        )
    }

    /**
     * Instagram hides search behind the same tab as the Explore grid. Blocking
     * that tab to keep the grid away must not also take away looking someone up.
     */
    @Test
    fun `the explore block leaves the search bar and the tab bar reachable`() {
        val band = bandFor("instagram-explore-idle", "com.instagram.android")
        val snapshot = fixture("instagram-explore-idle")

        val searchBottom = snapshot.nodes
            .first { it.viewIdMatches("action_bar_search_edit_text") }.bottomPx()!!
        val tabTop = snapshot.nodes.first { it.viewIdMatches("search_tab") }.topPx()!!

        assertTrue("must start below the search bar", band.topPx >= searchBottom)
        assertNotNull("must stop above the tab bar", band.heightPx)
        assertEquals("must end exactly at the tab bar", tabTop, band.topPx + band.heightPx!!)
    }

    @Test
    fun `the covered band still hides the explore grid itself`() {
        val band = bandFor("instagram-explore-idle", "com.instagram.android")
        val gridTop = fixture("instagram-explore-idle").nodes
            .filter { it.viewIdMatches("grid_card_layout_container") && it.visible }
            .mapNotNull { it.topPx() }.minOrNull()!!
        assertTrue(
            "the grid must be covered, otherwise the block does nothing",
            band.topPx <= gridTop,
        )
    }

    /**
     * Blocking the home feed used to swallow the stories tray with it. A story
     * is one person's post, not a feed, so the block starts below the tray.
     */
    @Test
    fun `the home feed block leaves the stories tray reachable`() {
        val app = rules().apps["com.instagram.android"]!!
        val rule = app.screens.first { it.id == "ig_feed" }
        val snapshot = fixture("instagram-home-with-stories")
        val band = OverlayBounds.compute(
            snapshot,
            rule.keepVisibleTopViewIds ?: app.keepVisibleTopViewIds,
            app.keepVisibleViewIds,
        )
        val trayBottom = snapshot.nodes
            .filter { it.viewIdMatches("outer_container") && it.visible }
            .mapNotNull { it.bottomPx() }.maxOrNull()!!
        assertEquals("must start exactly below the stories tray", trayBottom, band.topPx)
        assertNotNull("must still stop above the tab bar", band.heightPx)
    }

    /** Anchors are per screen: Explore's search bar must not move the feed block. */
    @Test
    fun `each screen uses its own anchor`() {
        val app = rules().apps["com.instagram.android"]!!
        val feedRule = app.screens.first { it.id == "ig_feed" }
        val exploreRule = app.screens.first { it.id == "ig_explore" }
        assertEquals(listOf("outer_container"), feedRule.keepVisibleTopViewIds)
        assertEquals(listOf("action_bar_search_edit_text"), exploreRule.keepVisibleTopViewIds)
    }

    @Test
    fun `youtube's blocked feed leaves its tab bar reachable`() {
        val band = bandFor("youtube-home", "com.google.android.youtube")
        assertNotNull(band.heightPx)
        assertTrue("cutoff suspiciously high", band.topPx + band.heightPx!! > 1500)
    }

    /** A screen with neither control, like the watch page, covers fully. */
    @Test
    fun `no anchors means covering everything rather than guessing`() {
        val band = bandFor("youtube-watch", "com.google.android.youtube")
        assertEquals(0, band.topPx)
        assertNull(band.heightPx)
    }
}
