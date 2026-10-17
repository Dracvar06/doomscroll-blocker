package cat.doorman.app.service

import cat.doorman.app.model.ScreenSnapshot

/**
 * Which stretch of watching a single-item allowance belongs to.
 *
 * An allowance is for one item that somebody sent, and it has to survive the
 * things people do while looking at it: open the comments, the share sheet, the
 * author's page. Those are screens Doorman cannot name, and the service used
 * to take "a screen I cannot name" for "the item was left", so coming back
 * counted as arriving at a new item. That was a free reel every time, and it
 * is why the allowance could be made endless.
 *
 * Kept pure so the awkward orderings have tests instead of a phone.
 */
object ScrollSession {

    data class Session(
        val packageName: String,
        val screenId: String,
        val key: String,
    )

    /** Whether the single-item screen now in front is a new item, with a new allowance. */
    fun startsFresh(session: Session?, packageName: String, screenId: String): Boolean =
        session == null || session.packageName != packageName || session.screenId != screenId

    /**
     * Whether the session is still going while something other than its screen
     * is in front.
     *
     * Only a screen the rules cannot name leaves it going. A named screen is
     * positively somewhere else, and [endViewIds] -- the conversation the next
     * item will come from -- says the same where the way there has no name. If
     * the tree cannot be read there is nothing to say it continues.
     */
    fun continues(
        session: Session?,
        packageName: String,
        recognisedScreenId: String?,
        snapshot: ScreenSnapshot?,
        endViewIds: List<String>,
    ): Boolean {
        if (session == null || snapshot == null) return false
        if (session.packageName != packageName) return false
        if (recognisedScreenId != null) return false
        return endViewIds.none { id -> snapshot.nodes.any { it.viewIdMatches(id) } }
    }
}
