package cat.doorman.app.rules

import cat.doorman.app.model.ScreenSnapshot

/**
 * Works out the slice of screen a block should cover.
 *
 * Covering everything corners the user. Two controls in particular have to stay
 * reachable from a blocked screen:
 *
 *  - the app's own tab bar, or the way to your messages is gone and the only
 *    exits are the back button and switching Doorman off;
 *  - the search bar, because Instagram puts search and the Explore grid behind
 *    one tab. Blocking that tab to keep the endless grid away also took away
 *    looking someone up, which is a thing people need and nothing to do with
 *    doomscrolling.
 *
 * So the overlay is a band: it starts below whatever must stay reachable at the
 * top and ends above whatever must stay reachable at the bottom.
 *
 * Pure, because "the user is never cornered" is the property this app lives or
 * dies by and it deserves tests that need no phone.
 */
object OverlayBounds {

    /**
     * Which top anchors apply, given what the user has switched on.
     *
     * A rule can name a switch that withdraws its anchors. That is how "hide the
     * stories row on the home feed" works: the row stops being protected and the
     * block extends over it, without changing whether stories themselves are
     * watchable. Kept pure so both outcomes are testable.
     */
    fun topAnchorsFor(
        rule: ScreenRule?,
        appLevelAnchors: List<String>,
        enabledScreenIds: Set<String>,
    ): List<String> {
        // Withdrawing a screen's own anchor falls back to the app's, rather than
        // to nothing. Asking for the stories row to be covered is not asking for
        // the search bar above it to be covered too, and on Snapchat those are
        // different views: the friends' row sits below the header, so dropping
        // to no anchor at all would take the search icon with it.
        if (rule?.keepVisibleTopSuppressedBy?.let { it in enabledScreenIds } == true) {
            return appLevelAnchors
        }
        return rule?.keepVisibleTopViewIds ?: appLevelAnchors
    }

    /** [heightPx] of null means "cover everything below [topPx]". */
    data class Band(val topPx: Int, val heightPx: Int?)

    fun compute(
        snapshot: ScreenSnapshot,
        keepVisibleTopViewIds: List<String>,
        keepVisibleViewIds: List<String>,
        keepVisibleBelowViewIds: List<String> = emptyList(),
    ): Band {
        val top = keepVisibleTopViewIds
            .takeIf { it.isNotEmpty() }
            ?.let { wanted ->
                snapshot.nodes
                    .filter { node -> node.visible && wanted.any(node::viewIdMatches) }
                    .mapNotNull { it.bottomPx() }
                    .maxOrNull()
            }
            ?.takeIf { it > 0 } ?: 0

        // Two ways of saying where the block ends, and the higher of the two
        // wins. Naming the tab bar is the clearer one; stopping at the bottom
        // of the content above it is the one that survives an app whose tab bar
        // has no stable name.
        val bottom = listOfNotNull(
            topOf(snapshot, keepVisibleViewIds),
            bottomOf(snapshot, keepVisibleBelowViewIds),
        ).minOrNull()

        // A band that would be inverted or absurdly thin means the measurements
        // disagree with each other; covering everything is the safe reading.
        if (bottom != null && bottom - top < MIN_BAND_PX) return Band(0, null)

        return Band(top, bottom?.let { it - top })
    }

    /** Top edge of the tab bar in absolute screen pixels, or null if not found. */
    fun topOf(snapshot: ScreenSnapshot, keepVisibleViewIds: List<String>): Int? {
        if (keepVisibleViewIds.isEmpty()) return null
        return snapshot.nodes
            .filter { node -> node.visible && keepVisibleViewIds.any(node::viewIdMatches) }
            .mapNotNull { it.topPx() }
            .minOrNull()
            ?.takeIf { it > 0 }
    }

    /**
     * Bottom edge of the content the block must stop at, or null if not found.
     *
     * The *smallest* bottom edge wins, not the largest. An app nests several
     * views under one name -- TikTok has three pagers called `viewpager`, one
     * per level -- and the outermost of them fills the whole screen, tab bar
     * included. Taking the largest would anchor to that and cover everything.
     * Taking the smallest errs towards covering too little, which leaves a feed
     * showing rather than trapping someone.
     */
    fun bottomOf(snapshot: ScreenSnapshot, keepVisibleBelowViewIds: List<String>): Int? {
        if (keepVisibleBelowViewIds.isEmpty()) return null
        return snapshot.nodes
            .filter { node -> node.visible && keepVisibleBelowViewIds.any(node::viewIdMatches) }
            .mapNotNull { it.bottomPx() }
            .minOrNull()
            ?.takeIf { it > 0 }
    }

    private const val MIN_BAND_PX = 200
}
