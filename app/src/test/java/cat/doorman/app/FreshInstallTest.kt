package cat.doorman.app

import cat.doorman.app.data.Prefs
import cat.doorman.app.data.ScreenPreferences
import cat.doorman.app.limits.Limits
import cat.doorman.app.rules.RuleSet
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What someone gets the first time they open Doorman, before touching
 * anything.
 *
 * These are the settings almost everyone will actually run, because most
 * people never change a default. They deserve to be stated somewhere that
 * fails when they drift rather than being an emergent property of three
 * unrelated files.
 */
class FreshInstallTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun rules(): RuleSet =
        json.decodeFromString(java.io.File("src/main/assets/rules.json").readText())

    private fun freshModes(): Map<String, Limits> {
        val defaults = rules().apps.values.flatMap { it.screens }
            .associate { it.id to it.defaultEnabled }
        return ScreenPreferences.effectiveLimits(
            screenDefaults = defaults,
            decided = emptySet(),
            enabled = emptySet(),
            stored = emptyMap(),
        )
    }

    /**
     * A fresh install and the "Balanced" preset have to be the same thing.
     * They are defined in different places -- one by defaultEnabled in
     * rules.json, the other by the preset button -- and a new screen that ships
     * on while Balanced leaves it off would mean tapping Balanced changed
     * settings nobody had touched.
     */
    @Test
    fun `a fresh install is exactly the balanced preset`() {
        val balanced = rules().apps.values.flatMap { it.screens }
            .filter { it.defaultEnabled }
            .map { it.id }
            .toSet()
        val blockedOnFreshInstall = freshModes()
            .filterValues { !it.isOff }
            .keys
        assertEquals(balanced, blockedOnFreshInstall)
    }

    /**
     * Nothing starts on an allowance. An allowance is a considered choice
     * about one screen, and handing someone five minutes of something they
     * never asked to have metered would be Doorman deciding for them.
     */
    @Test
    fun `nothing ships with a timer already running`() {
        val timed = freshModes().filterValues { it.allowances.isNotEmpty() }
        assertEquals(emptyMap<String, Limits>(), timed)
    }

    /**
     * Setting Doorman up means changing a dozen switches, and half a minute of
     * waiting on each one, before any benefit has been felt, is how an app gets
     * uninstalled during setup. The wait is one tap away for whoever wants it.
     */
    @Test
    fun `changes take effect at once until a wait is asked for`() {
        assertEquals(0, Prefs.DEFAULT_CHANGE_DELAY_SECONDS)
        assertEquals(Prefs.MIN_CHANGE_DELAY_SECONDS, Prefs.DEFAULT_CHANGE_DELAY_SECONDS)
        // Five minutes is the far end of the dial. Past that a wait stops being
        // a pause for thought and starts being a reason to uninstall the app.
        assertEquals(300, Prefs.MAX_CHANGE_DELAY_SECONDS)
    }

    /** Every app with rules of its own can be blocked without any setup. */
    @Test
    fun `every supported app blocks something out of the box`() {
        val silent = rules().apps.filterValues { app ->
            app.screens.none { it.defaultEnabled }
        }.keys
        assertEquals(emptySet<String>(), silent)
    }
}
