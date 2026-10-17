package cat.doorman.app.service

/**
 * Whether a block stays up while the app it covers is being swiped away.
 *
 * A swipe up from the bottom that is let go of halfway shows the home screen
 * for a moment and hands the app back. While it is held the launcher is the
 * active window, which used to take the block down -- and with it the cover on
 * a video that was still playing in a shrunken card.
 *
 * The app's own window is what tells the two apart: a real Home removes it from
 * the window list within about a third of a second, a held swipe does not.
 *
 * The hold is short on purpose. The recents screen leaves the app's window
 * listed too, and a block that stayed over recents would stand between someone
 * and the way out of the app. A swipe held for longer than [MAX_HOLD_MS] drops
 * the block, which is the price of never trapping anybody.
 *
 * Pure, so the cases have tests instead of a phone.
 */
object HoldPolicy {

    const val MAX_HOLD_MS = 1_200L

    fun keepBlock(
        blockedPackage: String?,
        present: Set<String>,
        interactive: Boolean,
        heldForMillis: Long,
    ): Boolean =
        blockedPackage != null &&
            interactive &&
            blockedPackage in present &&
            heldForMillis < MAX_HOLD_MS
}
