package cat.doorman.app.service

/**
 * The once-a-second tick that adds up time against a limit.
 *
 * Its one rule is that asking for it again never pushes it back. A playing
 * video changes the screen several times a second, every change is an
 * evaluation, and every evaluation asks for the tick. When asking meant
 * "cancel and schedule again", the tick never fired, nothing was ever added
 * up, and a ten-minute limit on a video let it run for as long as it ran.
 *
 * Takes the scheduling as functions so the rule can be tested on a fake clock;
 * the service hands it a Handler.
 */
class ClockTicker(
    private val periodMillis: Long,
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
    onTick: () -> Unit,
) {
    private var scheduled = false

    private val tick = Runnable {
        scheduled = false
        onTick()
    }

    /** A tick will come within [periodMillis]; one already on its way is left alone. */
    fun ensure() {
        if (scheduled) return
        scheduled = true
        post(tick, periodMillis)
    }

    fun cancel() {
        remove(tick)
        scheduled = false
    }
}
