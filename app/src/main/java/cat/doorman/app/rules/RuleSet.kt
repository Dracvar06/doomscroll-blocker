package cat.doorman.app.rules

import kotlinx.serialization.Serializable

/**
 * Screen rules live in assets/rules.json rather than in code, because the apps
 * being matched redesign themselves on their own schedule. Repairing a broken
 * fingerprint should be editing a JSON file, something a contributor can do
 * with the shipped dump tools and no Kotlin at all.
 */
@Serializable
data class RuleSet(
    val version: Int = 1,
    val apps: Map<String, AppRules> = emptyMap(),
)

@Serializable
data class AppRules(
    val labelKey: String,
    /**
     * Views the block must never cover -- in practice the app's own tab bar.
     *
     * Covering the whole screen traps people: from a blocked feed the way to
     * your messages is the tab bar, and if the overlay swallows those taps the
     * only exits left are the back button (which often lands on another blocked
     * screen) and switching Doorman off. An app that stands between someone and
     * their messages teaches them to bypass it, which is the failure this whole
     * design exists to avoid. So the feed is covered and the tab bar is not.
     */
    val keepVisibleViewIds: List<String> = emptyList(),
    val screens: List<ScreenRule> = emptyList(),
)

@Serializable
data class ScreenRule(
    val id: String,
    val labelKey: String,
    /** Every screen is opt-in; this is only what a fresh install starts with. */
    val defaultEnabled: Boolean = false,
    /**
     * "BLOCK" covers the screen outright. "ALLOW_ONCE" lets it be watched and
     * blocks only once the user moves past [budget] items -- which is how a reel
     * someone sent you stays watchable without becoming a feed.
     */
    val verdict: String = "BLOCK",
    val budget: Int = 1,
    val match: Matcher,
)

/**
 * All present conditions must hold. Ordered loosely by reliability:
 * view ids are stable within a version, selected-tab position survives
 * translation, and content descriptions are a last resort because they are
 * localised -- "Home" is "Inici" on a Catalan phone.
 */
@Serializable
data class Matcher(
    /**
     * A named view that is currently selected -- Instagram's tab bar gives each
     * tab its own id, so `clips_tab` being selected means Reels is genuinely in
     * front. Presence alone is not enough there: Instagram's ViewPager keeps
     * neighbouring tabs alive, so the DM inbox list, the feed and the explore
     * grid all sit in the tree at once no matter which tab you are looking at.
     */
    val selectedViewId: List<String>? = null,
    val anyViewId: List<String>? = null,
    val allViewId: List<String>? = null,
    val noneViewId: List<String>? = null,
    val selectedTab: SelectedTab? = null,
)

/** The selected item's position inside a tab strip, e.g. YouTube's pivot_bar. */
@Serializable
data class SelectedTab(
    val under: String,
    val index: Int,
)
