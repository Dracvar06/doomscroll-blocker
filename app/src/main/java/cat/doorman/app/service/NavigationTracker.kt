package cat.doorman.app.service

/**
 * Remembers how the user arrived at the screen they are looking at.
 *
 * A reel someone sent you and a reel you went looking for can be the very same
 * screen, so "what is in front of me" cannot tell them apart. What separates
 * them is the step before: arriving from a conversation, or from a link in
 * another app, means someone sent it. Arriving from the Reels tab means
 * browsing.
 *
 * Pure and clock-injected, so the awkward cases are testable without a device.
 */
class NavigationTracker(private val historyLimit: Int = 8) {

    data class Visit(val packageName: String, val screenId: String?, val atMillis: Long)

    sealed interface Entry {
        /** Arrived from a different app entirely -- a link in a messenger. */
        data object External : Entry

        /** Arrived from a named screen in the same app. */
        data class From(val screenId: String) : Entry

        /** Already wandering around inside the app. */
        data object InApp : Entry
    }

    private val history = ArrayDeque<Visit>()

    fun record(packageName: String, screenId: String?, atMillis: Long) {
        val last = history.lastOrNull()
        if (last?.packageName == packageName && last.screenId == screenId) return
        history.addLast(Visit(packageName, screenId, atMillis))
        while (history.size > historyLimit) history.removeFirst()
    }

    /**
     * How the current screen was reached. [screenId] is the screen now in front;
     * the answer comes from the most recent visit that was something else.
     */
    fun entryFor(packageName: String, screenId: String?): Entry {
        val previous = history.asReversed()
            .firstOrNull { it.packageName != packageName || it.screenId != screenId }
            ?: return Entry.External
        if (previous.packageName != packageName) return Entry.External
        return previous.screenId?.let { Entry.From(it) } ?: Entry.InApp
    }

    fun clear() = history.clear()
}
