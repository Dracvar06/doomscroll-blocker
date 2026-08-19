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

    /** [heightPx] of null means "cover everything below [topPx]". */
    data class Band(val topPx: Int, val heightPx: Int?)

    fun compute(
        snapshot: ScreenSnapshot,
        keepVisibleTopViewIds: List<String>,
        keepVisibleViewIds: List<String>,
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

        val bottom = topOf(snapshot, keepVisibleViewIds)

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

    private const val MIN_BAND_PX = 200
}
