package cat.doorman.app.service

import cat.doorman.app.limits.ReelQuota
import cat.doorman.app.limits.ReelQuotas
import cat.doorman.app.limits.ReelWindow

/**
 * Counts the reels watched on screens that have a quota, and says when one has
 * to close.
 *
 * It holds the part [ReelQuotas] cannot: what counts as being on a screen
 * *still*. Leaving Reels for a message and coming straight back is the same
 * visit and costs nothing; coming back after a couple of minutes is a new one,
 * and it counts a reel. Without that, glancing at your messages between reels
 * would be a way to watch them for free, and charging every re-check of the
 * screen would spend a whole quota on one reel.
 *
 * Pure: the clock is passed in and storage is a callback, so the awkward
 * orderings can be replayed in a test.
 */
class ReelCounter(
    private val sessionGapMillis: Long = DEFAULT_SESSION_GAP_MS,
) {

    sealed interface State {
        /** Watchable. [left] is how many reels the stretch still has after this one. */
        data class Open(val left: Int) : State

        /** Closed; it opens again at [untilMillis]. */
        data class Closed(val untilMillis: Long) : State
    }

    private class Session(val screenId: String, var lastSeenAt: Long)

    private var books: MutableMap<String, ReelWindow> = mutableMapOf()
    private var session: Session? = null

    /** Loads what was stored. Done once: the service is the only thing that writes. */
    fun load(stored: Map<String, ReelWindow>) {
        books = stored.toMutableMap()
    }

    /**
     * The screen is in front. Call on every look at it: the first look of a
     * visit counts a reel, and the rest only report where things stand.
     */
    fun onScreen(
        screenId: String,
        quota: ReelQuota,
        nowMillis: Long,
        persist: (String, ReelWindow) -> Unit,
    ): State {
        val here = session
        val continuing = here != null && here.screenId == screenId &&
            nowMillis - here.lastSeenAt <= sessionGapMillis
        if (continuing) {
            here!!.lastSeenAt = nowMillis
            val window = books[screenId]
            ReelQuotas.closedUntil(window, quota, nowMillis)?.let { return State.Closed(it) }
            // A block that has just lifted while somebody sat on it: what is on
            // screen now is the first reel of a new stretch, and counts as one.
            if (window?.over == true && ReelQuotas.current(window, quota, nowMillis) == null) {
                return enter(screenId, quota, nowMillis, persist)
            }
            return State.Open(ReelQuotas.remaining(window, quota, nowMillis))
        }
        session = Session(screenId, nowMillis)
        return enter(screenId, quota, nowMillis, persist)
    }

    private fun enter(
        screenId: String,
        quota: ReelQuota,
        nowMillis: Long,
        persist: (String, ReelWindow) -> Unit,
    ): State = when (val entry = ReelQuotas.enter(books[screenId], quota, nowMillis)) {
        is ReelQuotas.Entry.Allowed -> {
            store(screenId, entry.window, persist)
            State.Open(ReelQuotas.remaining(entry.window, quota, nowMillis))
        }
        is ReelQuotas.Entry.Closed -> {
            // Remembered as closed, so the visit's next looks keep it closed
            // rather than finding a quota that merely has no reels left.
            val current = ReelQuotas.current(books[screenId], quota, nowMillis)
            if (current != null) store(screenId, current.copy(over = true), persist)
            State.Closed(entry.untilMillis)
        }
    }

    /**
     * A swipe to the next reel. Returns the state to move into when it closes
     * the screen, and null while the next reel is allowed. Ignored outside a
     * visit, which a swipe event arriving ahead of the look at the screen is.
     */
    fun onSwipe(
        screenId: String,
        quota: ReelQuota,
        nowMillis: Long,
        persist: (String, ReelWindow) -> Unit,
    ): State.Closed? {
        val here = session ?: return null
        if (here.screenId != screenId) return null
        here.lastSeenAt = nowMillis
        return when (val swipe = ReelQuotas.swipe(books[screenId], quota, nowMillis)) {
            is ReelQuotas.Swipe.Next -> {
                store(screenId, swipe.window, persist)
                null
            }
            is ReelQuotas.Swipe.Closed -> {
                store(screenId, swipe.window, persist)
                State.Closed(swipe.untilMillis)
            }
        }
    }

    /** Where a screen stands, without counting anything. */
    fun window(screenId: String): ReelWindow? = books[screenId]

    /** The visit is over: the service is stopping, or nothing on screen has a quota. */
    fun reset() {
        session = null
    }

    private fun store(
        screenId: String,
        window: ReelWindow,
        persist: (String, ReelWindow) -> Unit,
    ) {
        books[screenId] = window
        persist(screenId, window)
    }

    companion object {
        /** The same two minutes the single-reel allowance uses to tell a visit from a return. */
        const val DEFAULT_SESSION_GAP_MS = 2 * 60 * 1000L
    }
}
