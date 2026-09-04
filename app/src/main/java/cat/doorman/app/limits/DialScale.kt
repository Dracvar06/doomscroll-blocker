package cat.doorman.app.limits

/**
 * Where the numbers sit on a duration dial, and how far round the dial goes.
 *
 * A budget dial has to cover two wildly different asks with the same thumb.
 * "Two minutes an hour" and "seven hours a week" are both things people
 * genuinely want, and a plain linear dial can only serve one of them: stretched
 * to forty-two hours, the first few minutes occupy a fraction of a degree, and
 * capped at an hour the second is unreachable.
 *
 * So the dial does not carry a continuous range. It carries a ladder of
 * sensible durations -- single minutes at first, then fives, tens, quarters of
 * an hour, and so on -- and spreads that ladder evenly around the turn. Every
 * position is the same distance from the next, so the dial feels uniform under
 * the finger while the numbers on it accelerate: near the bottom a turn of the
 * thumb is worth a minute, near the top it is worth three hours.
 *
 * The ladder is also why the dial never lands on an awkward number. Nobody
 * reaches for a budget of 2h 37m, and a control that offers it is a control
 * that makes you work to avoid it. Anyone who does want it can type it.
 */
object DialScale {

    /**
     * The shortest budget the dial offers, which is not the same for every
     * period.
     *
     * A minute matters when it is a minute an hour: the difference between one
     * and two is the difference between a glance and a visit. A minute a week
     * is not a budget anybody means, and putting fourteen of them at the bottom
     * of the weekly dial would crowd out the hours that dial is actually for.
     */
    fun shortestMinutes(period: Period): Int = when (period) {
        Period.HOUR, Period.DAY -> 1
        Period.WEEK -> 5
    }

    /**
     * How far the dial reaches for each period.
     *
     * One turn is one hour, six hours, or forty-two hours. Each is a limit that
     * still means something: an allowance of an hour every hour is no allowance
     * at all, and forty-two hours a week is six a day.
     */
    fun maxMinutes(period: Period): Int = when (period) {
        Period.HOUR -> 60
        Period.DAY -> 6 * 60
        Period.WEEK -> 42 * 60
    }

    /**
     * How far apart the rungs are around a given duration, never finer than
     * [finest].
     *
     * Coarser as the numbers grow, which is the whole trick: single minutes
     * matter under a quarter of an hour and are noise at thirty hours a week.
     */
    fun stepAt(minutes: Int, finest: Int = 1): Int = maxOf(
        finest,
        when {
            minutes < 15 -> 1
            minutes < 60 -> 5
            minutes < 120 -> 10
            minutes < 240 -> 15
            minutes < 480 -> 30
            minutes < 720 -> 60
            minutes < 1440 -> 120
            else -> 180
        },
    )

    /**
     * Every duration the dial can land on, in order, ending exactly on
     * [maxMinutes] so the top of the dial is a round number rather than
     * wherever the ladder happened to stop.
     */
    fun positions(maxMinutes: Int, finest: Int = 1): List<Int> {
        val values = mutableListOf<Int>()
        var minutes = finest
        while (minutes < maxMinutes) {
            values += minutes
            minutes += stepAt(minutes, finest)
        }
        values += maxMinutes
        return values
    }

    fun positions(period: Period): List<Int> =
        positions(maxMinutes(period), shortestMinutes(period))

    /**
     * Which rung a duration sits on, for placing the handle.
     *
     * Nearest rather than exact: a budget can also be typed in, and a typed
     * thirty-seven minutes has to put the handle somewhere sensible instead of
     * refusing or rounding itself away.
     */
    fun nearestIndex(positions: List<Int>, minutes: Int): Int {
        var best = 0
        positions.forEachIndexed { index, value ->
            if (Math.abs(value - minutes) < Math.abs(positions[best] - minutes)) best = index
        }
        return best
    }

    /**
     * A budget cut down to fit a period it has just been moved to.
     *
     * Switching "three hours a day" to "per hour" has to land somewhere, and an
     * hour is the most that period can mean. Left alone, the handle would sit
     * off the end of its own dial.
     */
    fun clampTo(minutes: Int, period: Period): Int =
        minutes.coerceIn(shortestMinutes(period), maxMinutes(period))
}
