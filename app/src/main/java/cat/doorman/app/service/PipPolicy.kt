package cat.doorman.app.service

import cat.doorman.app.limits.Allowances

/**
 * What to do about an app that has shrunk into its floating window.
 *
 * Swiping an app away does not stop it: YouTube turns the video into a small
 * player that keeps going over the home screen, and Doorman saw only the
 * launcher. The floating window is still the app, so what was true of the app
 * is still true of it: blocked stays blocked, and time on the clock keeps
 * running.
 *
 * Kept pure so the cases have tests instead of a phone.
 */
object PipPolicy {

    sealed interface Action {
        /** Nothing is held against it. */
        data object None : Action

        /** Cover the window and stop the sound. */
        data object Cover : Action

        /** Let it play, and count the time against these ledger keys. */
        data class Charge(val ledgerKeys: List<String>) : Action
    }

    /**
     * [blockedWhenLeft]: a block was standing over the app when it was swiped
     * away, whether for the whole app or for a screen in it. [appOutcome] is
     * what the whole-app limits say now, or null when there are none.
     *
     * A screen block cannot be re-asked of a floating window, because there is
     * no screen to read, so how the app was left is the answer. That is
     * deliberately the stricter of the two: a feed that was blocked when it was
     * swiped away should not be playable from the corner of the home screen.
     */
    fun decide(blockedWhenLeft: Boolean, appOutcome: Allowances.Outcome?): Action = when {
        appOutcome is Allowances.Outcome.Block -> Action.Cover
        blockedWhenLeft -> Action.Cover
        appOutcome is Allowances.Outcome.OnTheClock -> Action.Charge(appOutcome.charge)
        else -> Action.None
    }
}
