package cat.doorman.app.block

/**
 * Where to leave the floating window uncovered so it can still be closed.
 *
 * The cover has to hide the video, but the window's own close button must stay
 * reachable or the user is stuck with a window they cannot get rid of. The
 * system draws that button -- not the app -- in the top-right corner, about
 * 24dp in from each edge, once the window has been tapped; measured on a
 * Pixel 10 at two window sizes. The window is covered everywhere else, so what
 * shows through is the corner of the picture and nothing more.
 *
 * Pure, so the geometry has tests instead of a phone.
 */
object PipHole {

    /** A rectangle in the window's own coordinates, with the origin at its top-left. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** Touch targets are 48dp; a little extra covers the button not sitting exactly centred. */
    private const val HOLE_DP = 56

    /** A tiny window must not be left mostly open. */
    private const val MAX_FRACTION_PERCENT = 60

    fun hole(windowWidthPx: Int, windowHeightPx: Int, density: Float): Box {
        val wanted = (HOLE_DP * density).toInt()
        val limit = minOf(windowWidthPx, windowHeightPx) * MAX_FRACTION_PERCENT / 100
        val size = wanted.coerceIn(0, limit.coerceAtLeast(0))
        return Box(
            left = windowWidthPx - size,
            top = 0,
            right = windowWidthPx,
            bottom = size,
        )
    }

    /**
     * What has to be painted: the whole window except [hole], as two boxes.
     * Painting these rather than clearing a hole out of a full fill keeps the
     * window an ordinary translucent one with no offscreen layer.
     */
    fun covered(windowWidthPx: Int, windowHeightPx: Int, hole: Box): List<Box> = listOf(
        Box(0, 0, hole.left, windowHeightPx),
        Box(hole.left, hole.bottom, windowWidthPx, windowHeightPx),
    ).filter { it.width > 0 && it.height > 0 }
}
