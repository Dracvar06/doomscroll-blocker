package cat.doorman.app.limits

/**
 * How strictly one thing is held: a screen, or a whole app.
 *
 * Doorman began with a switch per screen, on or off. An allowance is the third
 * answer people actually want -- not "never" and not "whenever", but "five
 * minutes and then stop". Making it a mode rather than a separate feature keeps
 * one list of decisions instead of two, and everything that already understands
 * a switch keeps working: [Off] and [Blocked] are the old two states.
 */
sealed interface BlockMode {

    /** Not held at all. */
    data object Off : BlockMode

    /** Held always, which is what a switch turned on used to mean. */
    data object Blocked : BlockMode

    /**
     * Watchable for [minutes] in each [period], then held until the period
     * turns over.
     */
    data class Allowance(val minutes: Int, val period: Period) : BlockMode

    companion object {
        /** The allowances offered in the interface. */
        val OFFERED = listOf(
            Allowance(5, Period.HOUR),
            Allowance(15, Period.HOUR),
            Allowance(5, Period.DAY),
            Allowance(15, Period.DAY),
            Allowance(30, Period.DAY),
            Allowance(60, Period.DAY),
        )
    }
}

enum class Period { HOUR, DAY }

/**
 * How much this mode permits in a day, used only to compare two modes.
 *
 * It exists for the change delay. Loosening a decision has to wait, and
 * tightening one takes effect at once, so the two have to be ordered -- and
 * "five minutes an hour" against "thirty minutes a day" is not obvious until
 * both are put on the same scale. [Int.MAX_VALUE] for [BlockMode.Off] and zero
 * for [BlockMode.Blocked] put the old two states at the ends where they belong.
 */
val BlockMode.dailyMinutes: Int
    get() = when (this) {
        is BlockMode.Off -> Int.MAX_VALUE
        is BlockMode.Blocked -> 0
        is BlockMode.Allowance -> when (period) {
            Period.DAY -> minutes
            // Not a real forecast of use, just a common scale: five minutes an
            // hour is a looser rein than five minutes a day, and has to sort
            // that way.
            Period.HOUR -> minutes * 24
        }
    }

/**
 * True when moving from [from] to [to] gives the user more room.
 *
 * This is the question the change delay asks. Getting it backwards would let
 * someone lift a block on impulse, which is the whole thing the delay exists to
 * prevent, so it is stated once here and tested rather than re-derived at each
 * call site.
 */
fun isLoosening(from: BlockMode, to: BlockMode): Boolean =
    to.dailyMinutes > from.dailyMinutes
