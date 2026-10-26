package cat.doorman.app

import cat.doorman.app.rules.RuleSet
import cat.doorman.app.rules.TabTargets
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tap on a tab is only taken as heading for the screens that tab being selected recognises. */
class TabTargetsTest {

    private val rules: RuleSet = Json { ignoreUnknownKeys = true }
        .decodeFromString(java.io.File("src/main/assets/rules.json").readText())

    private val ig = rules.apps.getValue("com.instagram.android")

    @Test
    fun `instagram's tabs lead where the rules say`() {
        assertEquals(listOf("ig_feed"), TabTargets.screensFor(ig, "com.instagram.android:id/feed_tab"))
        assertEquals(listOf("ig_explore"), TabTargets.screensFor(ig, "com.instagram.android:id/search_tab"))
        assertEquals(listOf("ig_reels"), TabTargets.screensFor(ig, "com.instagram.android:id/clips_tab"))
    }

    /** The messages and your profile are never covered for being tapped. */
    @Test
    fun `tabs into screens that stay open lead nowhere blocked`() {
        assertTrue(TabTargets.screensFor(ig, "com.instagram.android:id/direct_tab").isEmpty())
        assertTrue(TabTargets.screensFor(ig, "com.instagram.android:id/profile_tab").isEmpty())
        assertTrue(TabTargets.screensFor(ig, "com.instagram.android:id/like_button").isEmpty())
    }
}
