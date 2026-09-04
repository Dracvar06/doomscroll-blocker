package cat.doorman.app

import cat.doorman.app.rules.RuleLoader
import cat.doorman.app.rules.RuleSet
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The quick settings, and the gap that used to sit between them.
 *
 * They ran "everything", "the usual", "nothing", and anyone who found the
 * middle one too much had nowhere left to go but off entirely. Permissive is
 * that missing step, and what defines it is not a taste but a property: whether
 * the screen ever ends.
 */
class PresetsTest {

    private val rules: RuleSet = Json { ignoreUnknownKeys = true }
        .decodeFromString(File("src/main/assets/rules.json").readText())

    private val endless = RuleLoader.endlessScreenIds(rules)
    private val balanced = RuleLoader.defaultEnabledScreenIds(rules)

    @Test
    fun `permissive is gentler than balanced, and both are gentler than everything`() {
        val everything = rules.apps.values.flatMap { it.screens }.map { it.id }.toSet()
        assertTrue("permissive should not hold anything balanced lets through",
            balanced.containsAll(endless))
        assertTrue(everything.containsAll(balanced))
        assertTrue("permissive must actually be gentler", endless.size < balanced.size)
    }

    /**
     * The line is whether a screen ends. These do, and the gentlest setting has
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

    /** Every app Doorman supports has something in the gentlest setting. */
    @Test
    fun `no app is left out of the gentlest setting`() {
        rules.apps.forEach { (packageName, app) ->
            assertTrue(packageName, app.screens.any { it.endless })
        }
    }
}
