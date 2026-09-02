package cat.doorman.app.service

import cat.doorman.app.limits.Allowances

/**
 * Counts time against whatever is currently on the clock.
 *
 * Kept apart from the service, and given its clock rather than reading one, so
 * that the fiddly parts have tests: time belongs to what was on screen *before*
 * the tick that noticed the change, not after, and a gap that spans the phone
 * being put down is not time spent at all.
 */
class AllowanceClock(private val nowMillis: () -> Long) {

    private val pending = mutableMapOf<String, Long>()
    private var lastTickAt: Long? = null
    private var charging: List<String> = emptyList()

    /**
     * Records that [targets] are being watched right now.
     *
     * The elapsed time is credited to whatever was being watched at the
     * previous tick, not to [targets]. Getting that backwards would charge the
     * screen someone has just arrived at for the minutes they spent on the one
     * before it.
     */
    fun tick(targets: List<String>) {
        val now = nowMillis()
        val credit = Allowances.creditableMillis(lastTickAt, now)
        if (credit > 0L) {
            charging.forEach { id -> pending[id] = (pending[id] ?: 0L) + credit }
        }
        lastTickAt = now
        charging = targets
    }

    /**
     * Nothing is being watched any more: credit the last stretch and stop.
     *
     * Called when the screen is blocked, when the user leaves the app, and when
     * the phone stops being interactive. Without it the next tick would credit
     * the whole absence to whatever was last on screen.
     */
    fun stop() {
        tick(emptyList())
        lastTickAt = null
    }

    /** Takes the accumulated time, leaving the clock empty. */
    fun drain(): Map<String, Long> {
        val out = pending.toMap()
        pending.clear()
        return out
    }

    fun pendingMillis(): Long = pending.values.sum()
}
