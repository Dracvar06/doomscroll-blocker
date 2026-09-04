package cat.doorman.app.limits

/**
 * The arithmetic behind a 24-hour dial, kept out of the drawing code so it can
 * be tested without a phone.
 *
 * Midnight sits at the top and the day runs clockwise, which is how every clock
 * face people have ever used works and therefore not a decision worth being
 * clever about.
 */
object RingGeometry {

    /** How finely a drag can land. */
    const val SNAP_MINUTES = 5

    /**
     * Degrees clockwise from the top for a minute of the day.
     */
    fun angleOf(minuteOfDay: Int): Float =
        (minuteOfDay.toFloat() / MINUTES_PER_DAY) * 360f

    /**
     * The minute of the day a touch at [degreesFromTop] means, snapped.
     *
     * Wrapped with floorMod rather than `%`, because dragging anticlockwise
     * past midnight produces a negative angle, and `%` in Kotlin keeps the sign:
     * a finger moved from 00:10 to 23:50 would come back as a negative minute
     * and the handle would jump to the far side of the dial.
     */
    fun minuteAt(degreesFromTop: Float): Int {
        // Wrapped in float space, not by truncating to a whole degree first:
        // one degree is four minutes, so rounding the angle before converting
        // moved 23:55 to 23:50 and made the last five minutes of the day
        // unreachable.
        val degrees = ((degreesFromTop % 360f) + 360f) % 360f
        val raw = (degrees / 360f) * MINUTES_PER_DAY
        val snapped = Math.round(raw / SNAP_MINUTES) * SNAP_MINUTES
        return Math.floorMod(snapped, MINUTES_PER_DAY)
    }

    /**
     * Degrees from the top for a touch at [x],[y] relative to the centre.
     *
     * atan2 measures anticlockwise from the positive x-axis with y pointing up;
     * a screen has y pointing down and the dial starts at the top, so the
     * arguments are swapped and the result rotated a quarter turn.
     */
    fun degreesFromTop(x: Float, y: Float): Float {
        val degrees = Math.toDegrees(kotlin.math.atan2(x.toDouble(), -y.toDouble())).toFloat()
        return (degrees + 360f) % 360f
    }

    /**
     * Which of the two handles a touch at [minute] is nearer, going the short
     * way round the dial.
     *
     * Picked once when a drag begins and then held for the rest of the gesture,
     * so the handles cannot swap under a finger that drags one past the other.
     */
    fun nearerHandleIsStart(minute: Int, startMinute: Int, endMinute: Int): Boolean =
        distanceAround(minute, startMinute) <= distanceAround(minute, endMinute)

    /**
     * Stops a drag from jumping across the seam where the dial's end meets its
     * beginning.
     *
     * On a dial that runs from nothing to a maximum, the two ends sit next to
     * each other, and a finger sliding down towards nothing easily carries on
     * past the top and lands on the maximum instead. For the change delay that
     * is a trap with teeth: overshooting "immediate" lands on five minutes, and
     * shortening the wait is itself subject to the wait, so a slip costs five
     * minutes before it can be undone.
     *
     * So a jump of more than half a turn is read as an overshoot rather than an
     * intention, and the dial is held at the end it was approaching. To reach
     * the maximum the long way round is the only way, which is the point.
     *
     * Measured in turns rather than in the dial's own units, because the units
     * are no longer evenly spaced around it: a budget dial's rungs are five
     * minutes apart at the bottom and three hours apart at the top, and half
     * the numbers is nowhere near half the way round.
     */
    fun withoutCrossingSeam(candidateTurn: Float, previousTurn: Float): Float = when {
        candidateTurn - previousTurn > HALF_TURN -> 0f
        previousTurn - candidateTurn > HALF_TURN -> 1f
        else -> candidateTurn
    }

    private const val HALF_TURN = 0.5f

    /** Minutes between two points on the dial, the short way round. */
    fun distanceAround(a: Int, b: Int): Int {
        val direct = Math.abs(a - b)
        return minOf(direct, MINUTES_PER_DAY - direct)
    }
}
