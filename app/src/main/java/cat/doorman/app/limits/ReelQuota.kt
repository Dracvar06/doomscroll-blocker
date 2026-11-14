package cat.doorman.app.limits

/**
 * "[reels] reels every [minutes] minutes" on one screen: ten an hour, or one
 * every quarter of an hour.
 *
 * One shape covers both of the ways people say it, which is why it is two
 * numbers and not a period. A calendar hour cannot say "one every fifteen
 * minutes", and a pause between reels cannot say "ten an hour"; a count within a
 * stretch of minutes says either.
 */
data class ReelQuota(
    val reels: Int,
    val minutes: Int,
) {
    val windowMillis: Long get() = minutes * 60_000L

    companion object {
        /** What the editor starts a new quota at: a reel, then a quarter of an hour. */
        val DEFAULT = ReelQuota(reels = 1, minutes = 15)
    }
}

/**
 * Where one screen stands inside its current stretch.
 *
 * [count] is the reels that have been *started*, the one on screen included.
 * [over] is set when somebody swiped past the last reel the quota allowed: the
 * stretch is then closed until it runs out, even though [count] says no more
 * than the quota -- which is also what it says while the last reel is still
 * playing, and that one has to stay watchable.
 */
data class ReelWindow(
    val startMillis: Long,
    val count: Int,
    val over: Boolean = false,
)

/**
 * Keeps the books on reels, and decides what a quota means right now.
 *
 * Pure, like [Allowances]: no clock, no storage, no Android. The awkward parts
 * are the stretch ending while a reel is on screen, a reel that is the last
 * allowed one still playing to its end, and coming back to it -- none of which
 * should need a phone and a stopwatch.
 *
 * A stretch starts with the first reel and lasts [ReelQuota.minutes], whatever
 * is done in it. It is not a calendar hour, unlike the minute allowances: "one
 * every fifteen minutes" has no calendar to be aligned to, and a stretch that
 * began at your first reel is the one a person can count from.
 */
object ReelQuotas {

    sealed interface Entry {
        /** The screen was opened, or opened again after a break: a reel is counted. */
        data class Allowed(val window: ReelWindow) : Entry

        /** The quota is used up; it opens again at [untilMillis]. */
        data class Closed(val untilMillis: Long) : Entry
    }

    sealed interface Swipe {
        /** The next reel is allowed and has been counted. */
        data class Next(val window: ReelWindow) : Swipe

        /** There was no next reel to allow: the screen closes until [untilMillis]. */
        data class Closed(val window: ReelWindow, val untilMillis: Long) : Swipe
    }

    /** The stretch [window] belongs to, or null once it has run out. */
    fun current(window: ReelWindow?, quota: ReelQuota, nowMillis: Long): ReelWindow? {
        if (window == null) return null
        // A clock set backwards would otherwise hold a stretch open for ever.
        val elapsed = nowMillis - window.startMillis
        if (elapsed < 0L || elapsed >= quota.windowMillis) return null
        return window
    }

    fun opensAt(window: ReelWindow, quota: ReelQuota): Long =
        window.startMillis + quota.windowMillis

    /** Somebody has come to the screen, from somewhere else or after a break. */
    fun enter(window: ReelWindow?, quota: ReelQuota, nowMillis: Long): Entry {
        val open = current(window, quota, nowMillis)
            ?: return Entry.Allowed(ReelWindow(nowMillis, 1))
        // The count alone decides. A stretch marked over whose quota has since
        // been raised above the count is open again: the reel it lands on is
        // counted, and the mark comes off.
        if (open.count >= quota.reels) {
            return Entry.Closed(opensAt(open, quota))
        }
        return Entry.Allowed(open.copy(count = open.count + 1, over = false))
    }

    /** Somebody has swiped to the next reel. */
    fun swipe(window: ReelWindow?, quota: ReelQuota, nowMillis: Long): Swipe {
        // The stretch ran out while a reel was playing: this swipe begins the
        // next one, and the reel it lands on is its first.
        val open = current(window, quota, nowMillis)
            ?: return Swipe.Next(ReelWindow(nowMillis, 1))
        if (open.count >= quota.reels) {
            val closed = open.copy(over = true)
            return Swipe.Closed(closed, opensAt(open, quota))
        }
        return Swipe.Next(open.copy(count = open.count + 1, over = false))
    }

    /**
     * When a screen that was closed by a swipe opens again, or null while it is
     * open. What a re-check of the screen asks, so the block stays up under it.
     * Judged against the quota as it is now: raised past the reels already
     * counted, the block lifts at once; lowered, nothing changes until a swipe.
     */
    fun closedUntil(window: ReelWindow?, quota: ReelQuota, nowMillis: Long): Long? {
        val open = current(window, quota, nowMillis) ?: return null
        return if (open.over && open.count >= quota.reels) opensAt(open, quota) else null
    }

    /** Reels the stretch still has, for saying so; zero once it is spent. */
    fun remaining(window: ReelWindow?, quota: ReelQuota, nowMillis: Long): Int {
        val open = current(window, quota, nowMillis) ?: return quota.reels
        return if (open.over && open.count >= quota.reels) 0 else (quota.reels - open.count).coerceAtLeast(0)
    }

    /**
     * True when [to] allows more reels than [from] did. A quota dropped
     * altogether is more; one set where there was none is not.
     */
    fun isLooser(from: ReelQuota?, to: ReelQuota?): Boolean {
        if (from == null) return false
        if (to == null) return true
        // reels per minute, compared without dividing
        return to.reels.toLong() * from.minutes > from.reels.toLong() * to.minutes
    }
}
