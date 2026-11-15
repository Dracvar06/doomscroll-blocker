package cat.doorman.app

import cat.doorman.app.rules.RuleLoader
import cat.doorman.app.rules.RuleSet
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Which screens never end, which is what the wizard's "just block the endless
 * parts" holds. What defines them is not a taste but a property: whether the
 * screen ever ends.
 */
class EndlessScreensTest {

    private val rules: RuleSet = Json { ignoreUnknownKeys = true }
        .decodeFromString(File("src/main/assets/rules.json").readText())

    private val endless = RuleLoader.endlessScreenIds(rules)

    /**
     * The line is whether a screen ends. These do, and the shortcut has
     * to leave them alone -- a home page you can reach the bottom of is not the
     * problem Doorman exists for.
     */
    @Test
    fun `the screens that end are left open`() {
        listOf("yt_home_feed", "yt_subscriptions_feed", "ig_feed", "ig_stories", "sc_stories")
            .forEach { assertFalse(it, it in endless) }
    }

    /** And these do not end, in any of the four apps. */
    @Test
    fun `the screens that never end are held`() {
        listOf("yt_shorts", "ig_reels", "tt_feed", "sc_spotlight")
            .forEach { assertTrue(it, it in endless) }
    }

    /** Every app Doorman supports has something the shortcut holds. */
    @Test
    fun `no app is left out of the shortcut`() {
        rules.apps.forEach { (packageName, app) ->
            assertTrue(packageName, app.screens.any { it.endless })
        }
    }
}
