package cat.doorman.app.rules

import cat.doorman.app.model.ScreenSnapshot

/**
 * Where, on the recent-apps screen, the card of a given app is.
 *
 * The card is a picture the system took as the app was left, and the block is
 * not in it: it is drawn over the app, not by it. So a blocked feed shows in
 * recents, still, for as long as the card is there to look at.
 *
 * Launchers label each card with its app's name for screen readers, which is
 * what is matched -- not view ids, which differ from one launcher to the next.
 * Only something card-tall counts: the home screen has an icon with the same
 * name, and covering an icon would be covering the wrong thing.
 *
 * Pure, so which node gets covered has tests instead of a phone.
 */
object RecentsCards {

    /**
     * A card is at least this share of the screen tall. Only the height counts:
     * the launcher reports a card scrolled to the edge cut down to the strip
     * still on screen, so its width says nothing.
     */
    private const val MIN_SHARE_PERCENT = 30

    fun find(snapshot: ScreenSnapshot, appLabel: String, screenWidth: Int, screenHeight: Int): EntryButtons.Box? {
        if (appLabel.isBlank() || screenWidth <= 0 || screenHeight <= 0) return null
        return snapshot.nodes.asSequence()
            .filter { it.visible && namesApp(it.contentDescription, appLabel) }
            .mapNotNull { node ->
                val parts = node.bounds?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
                if (parts == null || parts.size != 4) return@mapNotNull null
                val (l, t, r, b) = parts
                val cardSized = (b - t) * 100 >= screenHeight * MIN_SHARE_PERCENT
                val box = EntryButtons.Box(
                    l.coerceAtLeast(0), t.coerceAtLeast(0), r.coerceAtMost(screenWidth), b.coerceAtMost(screenHeight),
                )
                box.takeIf { cardSized && it.right > it.left && it.bottom > it.top }
            }
            .firstOrNull()
    }

    /** "Instagram", or the name followed by more: never another app whose name starts the same way. */
    fun namesApp(description: String?, label: String): Boolean {
        val d = description?.trim() ?: return false
        if (!d.startsWith(label, ignoreCase = true)) return false
        return d.length == label.length || !d[label.length].isLetterOrDigit()
    }
}
