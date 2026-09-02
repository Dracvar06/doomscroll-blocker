package cat.doorman.app.limits

/**
 * Turns a mode into a short string and back, for storage.
 *
 * Deliberately readable -- "blocked", "5h", "30d" -- because these end up in a
 * preferences file that someone debugging a bug report has to make sense of.
 *
 * Anything unrecognised decodes to null rather than to a guess. A stored value
 * this version does not understand is most likely one written by a *newer*
 * version, and inventing a mode for it could quietly unblock something the user
 * had held.
 */
object ModeCodec {

    fun encode(mode: BlockMode): String = when (mode) {
        is BlockMode.Off -> "off"
        is BlockMode.Blocked -> "blocked"
        is BlockMode.Allowance -> when (mode.period) {
            Period.HOUR -> "${mode.minutes}h"
            Period.DAY -> "${mode.minutes}d"
        }
    }

    fun decode(raw: String?): BlockMode? {
        if (raw.isNullOrBlank()) return null
        return when (raw) {
            "off" -> BlockMode.Off
            "blocked" -> BlockMode.Blocked
            else -> {
                val period = when (raw.last()) {
                    'h' -> Period.HOUR
                    'd' -> Period.DAY
                    else -> return null
                }
                val minutes = raw.dropLast(1).toIntOrNull() ?: return null
                if (minutes <= 0) return null
                BlockMode.Allowance(minutes, period)
            }
        }
    }
}
