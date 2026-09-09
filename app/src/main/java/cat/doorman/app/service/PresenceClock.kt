package cat.doorman.app.service

/**
 * Counts how long each held app, and each recognised screen inside it, is in
 * front of the user.
 *
 * The allowance clock ticks every second and refuses gaps over five, because
 * it is spending somebody's budget and must not over-charge. This one runs
 * whenever a held app is open, so it ticks slowly and accepts a longer gap --
 * a screen-time figure that is thirty seconds coarse is fine; one that drains
 * the battery to be exact is not.
 *
 * Time is credited to what was in front at the *previous* tick, never to what
 * has just arrived. A gap longer than [MAX_GAP_MS] is treated as the phone
 * having been away -- asleep, the service paused -- and credited to nothing.
 */
class PresenceClock(private val nowMillis: () -> Long) {

    private val pending = mutableMapOf<String, Long>()
    private var lastTickAt: Long? = null
    private var watching: List<String> = emptyList()

    fun tick(keys: List<String>) {
        val now = nowMillis()
        val last = lastTickAt
        if (last != null) {
            val gap = now - last
            if (gap in 1..MAX_GAP_MS) {
                watching.forEach { key -> pending[key] = (pending[key] ?: 0L) + gap }
            }
        }
        lastTickAt = now
        watching = keys
    }

    /** The user has left, the screen is off, or a block is up. */
    fun stop() {
        tick(emptyList())
        lastTickAt = null
    }

    fun drain(): Map<String, Long> {
        val out = pending.toMap()
        pending.clear()
        return out
    }

    companion object {
        const val MAX_GAP_MS = 90_000L
    }
}
