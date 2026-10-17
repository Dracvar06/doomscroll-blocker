package cat.doorman.app.service

/**
 * What one entry of the system's window list says, with no Android types in it
 * so the decision below can be tested on a plain JVM.
 */
data class WindowFact(
    val packageName: String?,
    /** An ordinary app window, as opposed to the status bar, a keyboard or one of our overlays. */
    val isApplication: Boolean,
    val active: Boolean,
    val focused: Boolean,
    /** The small floating player an app shrinks into when it is swiped away. */
    val pip: Boolean,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

/**
 * Works out which app is really in front from the window list.
 *
 * The last window-state event is a poor witness. An app being left raises one
 * *after* the app being entered does, a launcher can raise one from a pop-up
 * that is not in the window list at all, and a half-finished swipe home
 * produces a launcher event for an app that never left. Trusting the event
 * made the launcher the "current" app while YouTube was plainly in front, and
 * everything after that -- the block, the clock, the content events -- was
 * judged against the wrong package. The list says who is active right now.
 */
object Foreground {

    data class Result(
        /** The app the user is looking at, or null when the list cannot say. */
        val appPackage: String?,
        /** Apps shrunk into a floating window. Still playing, and still theirs to answer for. */
        val pip: List<WindowFact>,
    )

    /**
     * [ignore] holds the packages that appear over an app without the user
     * having gone anywhere: the shade, the keyboard, Doorman itself.
     *
     * Only an *active* window counts, with no falling back to the focused one.
     * While an app is opening, systemui holds both flags and the app being left
     * can still be focused; falling back to it would pull Doorman back to the
     * app the user just walked out of. Null means "no change to report".
     */
    fun resolve(windows: List<WindowFact>, ignore: Set<String>): Result {
        val apps = windows.filter {
            it.isApplication && it.packageName != null && it.packageName !in ignore
        }
        val pip = apps.filter { it.pip }
        val app = apps.firstOrNull { !it.pip && it.active }?.packageName
        return Result(app, pip)
    }
}
