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
 * The full block is only kept for [MAX_HOLD_MS]: it takes touches, and a block
 * that outlived the gesture would stand between someone and the way out of the
 * app. Past that, [card] hands over to a cover that takes no touches at all and
 * sits on the shrunken app alone, for as long as the system still shows it.
 * Dropping everything at that point used to leave the app in plain view for as
 * long as the thumb stayed down.
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

    /**
     * The window of the blocked app to cover while the launcher is in front, or
     * null when there is nothing to cover.
     *
     * Only a window that is visibly smaller than the screen counts. On a Pixel
     * the list reports the card where it is drawn, shrinking under the thumb,
     * and drops it once recents settles on a still picture. A phone that keeps
     * reporting the full screen would have the cover hide the home screen or
     * recents behind the card instead, so there it is not used, and the block
     * goes after [MAX_HOLD_MS] as before.
     */
    fun card(
        blockedPackage: String?,
        windows: List<WindowFact>,
        interactive: Boolean,
        canonical: (String) -> String,
    ): WindowFact? {
        if (blockedPackage == null || !interactive) return null
        val screen = windows.firstOrNull { it.isApplication && it.active && !it.pip } ?: return null
        val card = windows.firstOrNull {
            it.isApplication && !it.pip && !it.active && it.packageName != null &&
                canonical(it.packageName) == blockedPackage &&
                it.right > it.left && it.bottom > it.top &&
                it.left >= screen.left && it.top >= screen.top &&
                it.right <= screen.right && it.bottom <= screen.bottom &&
                (it.right - it.left < screen.right - screen.left || it.bottom - it.top < screen.bottom - screen.top)
        } ?: return null
        // The card grows back to full size faster than the list can follow it,
        // which showed the edges of the app around a cover a step behind. Near
        // full size -- the start of a swipe, the end of a return -- the whole
        // screen is covered instead.
        val nearlyFull = (card.right - card.left) * 100 >= (screen.right - screen.left) * NEARLY_FULL_PERCENT &&
            (card.bottom - card.top) * 100 >= (screen.bottom - screen.top) * NEARLY_FULL_PERCENT
        return if (nearlyFull) {
            card.copy(left = screen.left, top = screen.top, right = screen.right, bottom = screen.bottom)
        } else {
            card
        }
    }

    private const val NEARLY_FULL_PERCENT = 85
}
