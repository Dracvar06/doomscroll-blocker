package cat.doorman.app.service

/**
 * Lets a shared reel be watched once without letting it become a feed.
 *
 * The screen is allowed while the budget holds and blocked once it is spent.
 * Two independent signals spend it, because neither is reliable alone: the
 * pager reporting a new item index, and enough accumulated vertical scrolling
 * to have moved a screenful. Compose feeds in particular report indices
 * erratically, so distance is the backstop.
 *
 * Deliberately *not* spent by replaying, pausing, scrubbing, or horizontal
 * swipes, which open a profile rather than a new reel. Someone should be able
 * to actually watch the thing they were sent.
 */
class ScrollBudget(
    private val viewportHeightPx: () -> Int,
    private val sessionGapMillis: Long = 2 * 60 * 1000,
) {

    private data class State(
        val key: String,
        var remaining: Int,
        var accumulatedY: Int,
        var lastItemIndex: Int?,
        var lastSeenAt: Long,
        val startedAt: Long,
        val allowMillis: Long?,
    )

    private var state: State? = null

    /**
     * Starts or resumes a budget for [key]. Returning within [sessionGapMillis]
     * resumes the same budget rather than granting a fresh one -- otherwise
     * leaving Instagram and coming back is a one-tap way to keep scrolling.
     */
    fun begin(key: String, budget: Int, nowMillis: Long, allowMillis: Long? = null) {
        val current = state
        if (current != null && current.key == key &&
            nowMillis - current.lastSeenAt <= sessionGapMillis
        ) {
            current.lastSeenAt = nowMillis
            return
        }
        state = State(key, budget, 0, null, nowMillis, nowMillis, allowMillis)
    }

    fun hasBudget(key: String, nowMillis: Long): Boolean {
        val current = state ?: return false
        if (current.key != key) return false
        current.lastSeenAt = nowMillis
        if (current.remaining <= 0) return false
        return remainingMillis(nowMillis, current) != 0L
    }

    /**
     * How long this allowance still has, or null when nothing is running and
     * when it is the swipe alone that ends it. Lets the caller arm the block
     * rather than wait for an event: a playing Short raises none at all.
     */
    fun remainingMillis(key: String, nowMillis: Long): Long? {
        val current = state ?: return null
        if (current.key != key) return null
        return remainingMillis(nowMillis, current)
    }

    private fun remainingMillis(nowMillis: Long, current: State): Long? {
        val allowed = current.allowMillis ?: return null
        return (current.startedAt + allowed - nowMillis).coerceAtLeast(0)
    }

    /**
     * Feeds a scroll event in. [itemIndex] is the pager's current position when
     * it reports one, and [scrolledViewHeightPx] the height of the view that
     * actually scrolled, when the event names one. Returns true when the budget
     * has just run out.
     */
    fun onScroll(
        key: String,
        deltaX: Int,
        deltaY: Int,
        itemIndex: Int?,
        nowMillis: Long,
        scrolledViewHeightPx: Int? = null,
    ): Boolean {
        val current = state ?: return false
        if (current.key != key) return false
        current.lastSeenAt = nowMillis
        if (current.remaining <= 0) return false

        // Sideways gestures open a profile; they are not a new reel.
        if (kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY)) return false

        var moved = false
        if (itemIndex != null) {
            val previous = current.lastItemIndex
            if (previous != null && itemIndex != previous) moved = true
            current.lastItemIndex = itemIndex
        }

        current.accumulatedY += kotlin.math.abs(deltaY)
        // Measured against the view that scrolled rather than the screen. On
        // YouTube the Shorts pager sits between the status bar and the
        // navigation bar, and reports 1031px for a page turn on a 2062px tall
        // recycler -- a whole Short, but comfortably less than half a 2424px
        // screen. Judged against the screen, the first swipe past a Short
        // someone sent counted for nothing and a second Short played free.
        val viewport = (scrolledViewHeightPx?.takeIf { it > 0 } ?: viewportHeightPx())
            .coerceAtLeast(1)
        if (current.accumulatedY >= viewport * PAGE_TURN_FRACTION / 100) moved = true

        if (!moved) return false
        current.remaining -= 1
        current.accumulatedY = 0
        return current.remaining <= 0
    }

    fun reset() {
        state = null
    }

    private companion object {
        /**
         * How much of the scrolling view has to pass before the feed counts as
         * having moved on, as a percentage. Deliberately below the half a real
         * page turn reports: pagers snap, so the choice is between "a page" and
         * "nothing", and a drag that springs back raises no scroll event at all
         * on any of the feeds Doorman holds. The margin is there so a page turn
         * reported a few pixels short still counts.
         */
        const val PAGE_TURN_FRACTION = 40
    }
}
