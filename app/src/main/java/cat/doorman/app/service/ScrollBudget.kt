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
    )

    private var state: State? = null

    /**
     * Starts or resumes a budget for [key]. Returning within [sessionGapMillis]
     * resumes the same budget rather than granting a fresh one -- otherwise
     * leaving Instagram and coming back is a one-tap way to keep scrolling.
     */
    fun begin(key: String, budget: Int, nowMillis: Long) {
        val current = state
        if (current != null && current.key == key &&
            nowMillis - current.lastSeenAt <= sessionGapMillis
        ) {
            current.lastSeenAt = nowMillis
            return
        }
        state = State(key, budget, 0, null, nowMillis)
    }

    fun hasBudget(key: String, nowMillis: Long): Boolean {
        val current = state ?: return false
        if (current.key != key) return false
        current.lastSeenAt = nowMillis
        return current.remaining > 0
    }

    /**
     * Feeds a scroll event in. [itemIndex] is the pager's current position when
     * it reports one. Returns true when the budget has just run out.
     */
    fun onScroll(
        key: String,
        deltaX: Int,
        deltaY: Int,
        itemIndex: Int?,
        nowMillis: Long,
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
        val viewport = viewportHeightPx().coerceAtLeast(1)
        if (current.accumulatedY >= viewport / 2) moved = true

        if (!moved) return false
        current.remaining -= 1
        current.accumulatedY = 0
        return current.remaining <= 0
    }

    fun reset() {
        state = null
    }
}
