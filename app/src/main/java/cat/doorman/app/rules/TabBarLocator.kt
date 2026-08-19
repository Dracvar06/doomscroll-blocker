package cat.doorman.app.rules

import cat.doorman.app.model.ScreenSnapshot

/**
 * Finds where the app's own tab bar starts, so the block can stop above it.
 *
 * Pure, because "the route to your messages stays reachable" is the single most
 * important property this app has and it deserves a test that does not need a
 * phone. Covering the tab bar corners the user: the back button often lands on
 * another blocked screen, so the only remaining exit is switching Doorman off.
 */
object TabBarLocator {

    /** Top edge of the tab bar in absolute screen pixels, or null if not found. */
    fun topOf(snapshot: ScreenSnapshot, keepVisibleViewIds: List<String>): Int? {
        if (keepVisibleViewIds.isEmpty()) return null
        return snapshot.nodes
            .filter { node -> node.visible && keepVisibleViewIds.any(node::viewIdMatches) }
            .mapNotNull { it.topPx() }
            .minOrNull()
            ?.takeIf { it > 0 }
    }
}
