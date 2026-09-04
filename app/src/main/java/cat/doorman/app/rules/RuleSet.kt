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
) {
    /**
     * Every package any rule speaks for, main keys and [AppRules.aliases] alike.
     *
     * The service used to carry its own copy of this list. That is the kind of
     * duplication that fails quietly: adding an app to rules.json while
     * forgetting the second list produces no error, no crash and no blocking --
     * just an app that mysteriously does nothing. Derive it instead.
     */
    val supportedPackages: Set<String> by lazy {
        apps.keys + apps.values.flatMap { it.aliases }
    }

    /**
     * The rules for a package, whether it arrived under its main name or one of
     * its aliases.
     */
    fun appFor(packageName: String?): AppRules? {
        if (packageName == null) return null
        apps[packageName]?.let { return it }
        return apps.values.firstOrNull { packageName in it.aliases }
    }

    /**
     * The name an app is filed under here, whichever of its names it arrived as.
     *
     * A pass is granted for "TikTok" but spent against whatever package is in
     * front, and on a regional build those are two different strings. Comparing
     * them directly would hand someone a pass that does nothing.
     */
    fun canonicalPackage(packageName: String?): String? {
        if (packageName == null) return null
        if (packageName in apps) return packageName
        return apps.entries.firstOrNull { packageName in it.value.aliases }?.key
    }
}

@Serializable
data class AppRules(
    val labelKey: String,
    /**
     * Other package names that are the same app and share these rules.
     *
     * TikTok is the reason this exists: it ships as com.zhiliaoapp.musically in
     * most of the world and com.ss.android.ugc.trill elsewhere, and Snapchat
     * has had regional builds too. Keying rules by one name would leave those
     * users with an app that silently blocks nothing.
     */
    val aliases: List<String> = emptyList(),
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
    /**
     * Views at the top of the screen the block must stay clear of -- the search
     * bar. Instagram hides search behind the same tab as the Explore grid, so
     * covering the whole tab to keep the grid away also removed the ability to
     * look somebody up.
     */
    val keepVisibleTopViewIds: List<String> = emptyList(),
    /**
     * Views whose *bottom* edge the block must stop at, leaving everything
     * below them reachable.
     *
     * [keepVisibleViewIds] names the tab bar directly, which is only possible
     * when the tab bar has a name. TikTok's does not: every id in it is
     * obfuscated and changes with each release, and an anchor that stops
     * matching would let the block cover the whole screen and corner the user --
     * the one outcome this app must never produce.
     *
     * So the anchor is inverted. TikTok's content pager keeps a real name, and
     * its bottom edge is exactly the top of the tab bar. Anchoring to the thing
     * that has a stable name and stopping where it ends survives the rename,
     * and when it does break it breaks by covering too little rather than too
     * much.
     */
    val keepVisibleBelowViewIds: List<String> = emptyList(),
    val screens: List<ScreenRule> = emptyList(),
) {
    /** Every name this app goes by, its key included. */
    fun packages(key: String): List<String> = listOf(key) + aliases
}

@Serializable
data class ScreenRule(
    val id: String,
    val labelKey: String,
    /** Every screen is opt-in; this is only what a fresh install starts with. */
    val defaultEnabled: Boolean = false,
    /**
     * Whether the gentlest preset still holds this screen.
     *
     * The line is whether the screen *ends*. A home page, a subscriptions list
     * and a friend's stories all run out; Shorts, Reels, Spotlight and the For
     * You feed are built never to. Someone who wants Doorman out of the way as
     * much as possible still wants the ones that never end.
     *
     * Data rather than a list in the code, so adding an app means editing one
     * file and the presets follow.
     */
    val endless: Boolean = false,
    /**
     * "BLOCK" covers the screen outright. "ALLOW_ONCE" lets it be watched and
     * blocks only once the user moves past [budget] items -- which is how a reel
     * someone sent you stays watchable without becoming a feed.
     */
    val verdict: String = "BLOCK",
    val budget: Int = 1,
    /**
     * Views this particular block must stay clear of at the top of the screen,
     * overriding the app-wide list.
     *
     * Anchors belong to a screen, not to an app. The home feed's anchor is the
     * stories tray; Explore's is the search bar. Pooling them app-wide would let
     * one screen's anchor push another screen's block out of place, and the id
     * that marks the stories tray is generic enough for that to happen.
     */
    val keepVisibleTopViewIds: List<String>? = null,
    /**
     * The id of a switch that, when the user turns it on, cancels
     * [keepVisibleTopViewIds] so the block covers those views too.
     *
     * Hiding the stories row on the home feed and blocking stories outright are
     * different wants: someone may not want the row tempting them every time
     * they open the app, while still wanting to watch a story a friend sends
     * them. So the row is an anchor that a separate switch can withdraw.
     */
    val keepVisibleTopSuppressedBy: String? = null,
    /**
     * Null means this entry is not a screen at all: it is a switch that changes
     * how another block behaves, and it must never match anything by itself.
     */
    val match: Matcher? = null,
    /**
     * A string naming what this block actually covers, shown behind an info
     * button next to it.
     *
     * "Reels" and "keep swiping from a reel someone sent" are not the same
     * thing, and neither is obvious from a two-word label. Someone who cannot
     * tell which switch does what will either block too much and resent the
     * app, or block too little and think it is broken.
     */
    val helpKey: String? = null,
) {
    /**
     * Whether it makes sense to offer this one an allowance.
     *
     * Only screens people spend time in. "Keep swiping from a video someone
     * sent" is a behaviour, and the stories row is a strip of the home feed;
     * neither is a place you can be for five minutes, so both stay plain
     * switches rather than being offered a nonsensical choice.
     */
    val supportsAllowance: Boolean
        get() = match != null && verdict == "BLOCK"
}

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
    /**
     * These views must be present *and* actually on screen.
     *
     * Presence alone is a trap on Instagram: its ViewPager keeps neighbouring
     * tabs alive, so the Reels pager sits in the tree while you are searching,
     * reading your profile or looking at the Explore grid. Matching on presence
     * made the search screen look like a reel viewer, which would have blocked
     * someone mid-search. Visibility is what distinguishes a screen that is in
     * front from one that merely exists.
     */
    val visibleViewId: List<String>? = null,
    val anyViewId: List<String>? = null,
    val allViewId: List<String>? = null,
    val noneViewId: List<String>? = null,
    val selectedTab: SelectedTab? = null,
    /**
     * True to match only when the user arrived from a different app, false to
     * match only when they did not. Absent means the question is not asked.
     *
     * Some screens cannot be told apart by looking at them. Open a TikTok link
     * a friend sent and TikTok does not show you an isolated video the way
     * Instagram shows a reel from a DM: it drops you into the For You feed
     * itself, same top tabs, same tab bar, same everything. Watching what
     * somebody sent you and browsing the feed are, on screen, the same pixels.
     * The only thing that separates them is the step before -- whether you came
     * from a conversation or from your home screen.
     *
     * The launcher does not count as another app, or opening TikTok deliberately
     * would look exactly like following a link and hand out a free video every
     * time.
     */
    val arrivedFromAnotherApp: Boolean? = null,
)

/** The selected item's position inside a tab strip, e.g. YouTube's pivot_bar. */
@Serializable
data class SelectedTab(
    val under: String,
    val index: Int,
)
