package cat.doorman.app

import cat.doorman.app.model.ScreenSnapshot
import cat.doorman.app.rules.RuleSet
import cat.doorman.app.rules.TabBarLocator
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A block that covers the tab bar leaves no way to reach your messages except
 * turning the app off, which is the habit this whole design exists to prevent.
 */
class TabBarLocatorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): ScreenSnapshot =
        json.decodeFromString(
            javaClass.classLoader!!.getResourceAsStream("fixtures/$name.json")!!
                .bufferedReader().readText()
        )

    private fun rules(): RuleSet =
        json.decodeFromString(java.io.File("src/main/assets/rules.json").readText())

    @Test
    fun `instagram's tab bar is found and sits below the content`() {
        val snapshot = fixture("instagram-clips")
        val keep = rules().apps["com.instagram.android"]!!.keepVisibleViewIds
        assertTrue("rules must name the tab bar", keep.isNotEmpty())

        val top = TabBarLocator.topOf(snapshot, keep)
        assertNotNull("tab bar must be locatable on a blocked screen", top)
        // Well down the screen: a cutoff near the top would blank the app while
        // still hiding the tabs, which is worse than covering everything.
        assertTrue("cutoff suspiciously high: $top", top!! > 1500)
    }

    @Test
    fun `youtube's tab bar is found on the blocked home feed`() {
        val keep = rules().apps["com.google.android.youtube"]!!.keepVisibleViewIds
        val top = TabBarLocator.topOf(fixture("youtube-home"), keep)
        assertNotNull(top)
        assertTrue("cutoff suspiciously high: $top", top!! > 1500)
    }

    /** A screen with no tab bar, like the YouTube watch page, covers fully. */
    @Test
    fun `no tab bar means no cutoff rather than a wrong one`() {
        val keep = rules().apps["com.google.android.youtube"]!!.keepVisibleViewIds
        assertNull(TabBarLocator.topOf(fixture("youtube-watch"), keep))
        assertNull(TabBarLocator.topOf(fixture("instagram-clips"), emptyList()))
    }
}
