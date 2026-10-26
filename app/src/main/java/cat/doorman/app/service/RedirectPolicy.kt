package cat.doorman.app.service

/**
 * When a block has held someone long enough to send them somewhere else.
 *
 * Not when it lands. Plenty of blocks are walked straight through: Instagram
 * opens on its home feed, so going to your messages means meeting the feed's
 * block for a second on the way. Being thrown into a book app then would be
 * the app misreading you, and an app that misreads you gets switched off.
 *
 * What tells the two apart is time. On the way somewhere you are gone in a
 * moment; still there after [DWELL_MS], with the screen covered and nothing to
 * do on it, you are waiting for the feed to come back. Using what the block
 * leaves open around it -- the stories row, the top tabs -- is doing something,
 * so it starts the wait again rather than counting towards it.
 *
 * Pure, so the timing has tests instead of a phone.
 */
object RedirectPolicy {

    /** How long a block has to stand before it sends you on. */
    const val DWELL_MS = 5_000L

    /**
     * After sending you somewhere, how long before doing it again by itself.
     * Coming back on purpose -- slowly, through the feed, to read a message --
     * should not be bounced straight out again. The button on the block still
     * works at any time.
     */
    const val QUIET_MS = 60_000L

    /**
     * Whether to send you on now. Times are one clock's milliseconds;
     * [lastRedirectAt] of null means never.
     */
    fun due(
        shownAt: Long,
        lastActivityAt: Long,
        lastRedirectAt: Long?,
        now: Long,
        interactive: Boolean,
    ): Boolean {
        if (!interactive) return false
        if (lastRedirectAt != null && now - lastRedirectAt < QUIET_MS) return false
        return now - maxOf(shownAt, lastActivityAt) >= DWELL_MS
    }

    /** When to look again, given what is known now. */
    fun nextCheckAt(shownAt: Long, lastActivityAt: Long, lastRedirectAt: Long?): Long {
        val dwellEnds = maxOf(shownAt, lastActivityAt) + DWELL_MS
        val quietEnds = lastRedirectAt?.plus(QUIET_MS) ?: 0L
        return maxOf(dwellEnds, quietEnds)
    }
}
