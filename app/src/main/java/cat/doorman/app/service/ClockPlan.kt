package cat.doorman.app.service

import cat.doorman.app.limits.Allowances
import cat.doorman.app.limits.HardTimes
import cat.doorman.app.limits.Limits
import java.time.LocalDateTime

/**
 * Which allowances the clock counts against, once the screen has been judged.
 *
 * Pure, so that the two places where the answer is not simply "whatever the
 * decision says" can be tested without a service.
 */
object ClockPlan {

    /**
     * Ledger keys to count against after a decision; empty when the clock
     * stands still. Nothing is counted while the reel quota's block covers the
     * screen: the minutes were not watched, and charging them ended in "time is
     * up" for time nobody saw.
     */
    fun afterDecision(outcome: Allowances.Outcome, reelsClosed: Boolean): List<String> =
        if (outcome is Allowances.Outcome.OnTheClock && !reelsClosed) outcome.charge else emptyList()

    /**
     * Ledger keys to count against on a screen that is let through once. That
     * pass-through is not undone by a spent allowance or by hours, but the time
     * on it is still time on the app, so it is counted -- spent or not, since
     * nothing is being enforced here.
     */
    fun onAllowOnce(targets: List<Allowances.Target>, now: LocalDateTime): List<String> =
        Allowances.chargeKeys(targets, now.dayOfWeek)

    /**
     * What becomes of a single video -- a reel, a Short -- once the time limits
     * are asked about it.
     */
    sealed interface SingleVideo {
        /** Let through; the clock runs on [charge], which may be empty. */
        data class Counted(val charge: List<String>) : SingleVideo

        /** The screen it was opened from is out of time, and so is this. */
        data class Closed(val block: Allowances.Outcome.Block) : SingleVideo
    }

    /**
     * The screen a single video was opened from, as a target, when that screen
     * is one the user limits by time; null otherwise.
     *
     * A video opened from the feed is the feed carrying on: tapping it does not
     * make the minutes any less spent on the feed. A video opened from a
     * conversation, a profile or search was sent or found, not browsed to, and
     * those have no limit to carry. Where the screen it came from is not known,
     * or the video was reached by a link from another app, there is no origin
     * -- the failure to avoid is a block over something nobody limited.
     *
     * A screen held outright is not an origin: the user cannot have been on it.
     *
     * An app-level limit scoped to some screens ([Limits.onlyOn]) is an origin
     * too when the video was opened from one of them: it is the shared budget
     * carrying on. A screen's own limit comes first when it has one.
     */
    fun originOf(
        entry: NavigationTracker.Entry,
        enabledScreenIds: Set<String>,
        screenLimits: Map<String, Limits>,
        spent: Map<String, Allowances.Spent>,
        appId: String? = null,
        appLimits: Limits? = null,
    ): Allowances.Target? {
        val from = (entry as? NavigationTracker.Entry.From)?.screenId ?: return null
        val screen = screenLimits[from]
        if (from in enabledScreenIds && screen != null && timed(screen)) {
            return Allowances.Target(from, screen, spent)
        }
        if (appId != null && appLimits?.onlyOn != null && from in appLimits.onlyOn && timed(appLimits)) {
            return Allowances.Target(appId, appLimits, spent)
        }
        return null
    }

    private fun timed(limits: Limits): Boolean =
        !limits.blocked && (limits.allowances.isNotEmpty() || limits.windows.isNotEmpty())

    /**
     * A single video is let through however the app's own limit stands; that
     * pass-through is [counted] time on the app and on the video's own screen.
     * Only the origin, when there is one, can close it: its hours and its spent
     * allowance apply to the video as they did to the screen it was opened
     * from, and its running allowances are charged as if still there.
     */
    fun onSingleVideo(
        origin: Allowances.Target?,
        counted: List<Allowances.Target>,
        now: LocalDateTime,
        hard: HardTimes = HardTimes.EMPTY,
    ): SingleVideo {
        val own = onAllowOnce(counted, now)
        if (origin == null) return SingleVideo.Counted(own)
        return when (val outcome = Allowances.decide(listOf(origin), now, hard)) {
            is Allowances.Outcome.Block -> SingleVideo.Closed(outcome)
            is Allowances.Outcome.OnTheClock -> SingleVideo.Counted((outcome.charge + own).distinct())
            Allowances.Outcome.Allow -> SingleVideo.Counted(own)
        }
    }
}
