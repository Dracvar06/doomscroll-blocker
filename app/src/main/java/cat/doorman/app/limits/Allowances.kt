package cat.doorman.app.limits

import java.time.LocalDateTime

/**
 * Keeps the books on time already spent, and decides what that means for the
 * screen in front of the user.
 *
 * Pure: no clock of its own, no storage, no Android. The awkward parts of an
 * allowance are the turn of the hour, two allowances running at once, and the
 * moment the last second is spent, and none of those should need a phone and a
 * stopwatch to test.
 */
object Allowances {

    /** Time spent against one target, and the period it was spent in. */
    data class Spent(val periodKey: String, val millis: Long) {
        companion object {
            val NOTHING = Spent("", 0L)
        }
    }

    /**
     * Names the stretch of time an allowance is measured over.
     *
     * Calendar periods, not a rolling window: someone should be able to say
     * when their next five minutes arrive without doing arithmetic. The cost is
     * that an allowance spent at the end of one hour is followed straight away
     * by the next hour's, which is a real hole and a deliberate trade -- a
     * rolling window is stricter but nobody can predict it.
     */
    fun periodKey(period: Period, now: LocalDateTime): String = when (period) {
        Period.DAY -> now.toLocalDate().toString()
        Period.HOUR -> "${now.toLocalDate()}T${now.hour}"
    }

    /**
     * Time already spent in the current period. A record from an earlier period
     * counts as nothing, which is how an allowance renews without anything
     * having to run at midnight.
     */
    fun spentInCurrentPeriod(spent: Spent, period: Period, now: LocalDateTime): Long =
        if (spent.periodKey == periodKey(period, now)) spent.millis else 0L

    fun remainingMillis(
        mode: BlockMode.Allowance,
        spent: Spent,
        now: LocalDateTime,
    ): Long = (mode.minutes * 60_000L - spentInCurrentPeriod(spent, mode.period, now))
        .coerceAtLeast(0L)

    /**
     * One thing being held, with whatever has been spent against it.
     *
     * [id] is a screen id or a package name. The two share a namespace, which is
     * safe because package names contain dots and screen ids do not.
     */
    data class Target(val id: String, val mode: BlockMode, val spent: Spent = Spent.NOTHING)

    sealed interface Outcome {
        data object Allow : Outcome

        /** Held, by [targetId]. */
        data class Block(val targetId: String, val allowanceSpent: Boolean) : Outcome

        /**
         * Watchable, but the clock is running on [charge] -- the ids that time
         * should be counted against. [remainingMillis] is how long until the
         * tightest of them runs out.
         */
        data class OnTheClock(
            val charge: List<String>,
            val remainingMillis: Long,
        ) : Outcome
    }

    /**
     * What to do about a screen, given every target that applies to it.
     *
     * More than one applies at once on purpose: an allowance can be set on
     * Instagram as a whole and on Reels within it, and the answer the user
     * expects is that whichever runs out first stops them. So anything held
     * outright wins, then any allowance already spent, and otherwise the clock
     * runs on all of them together.
     */
    fun decide(targets: List<Target>, now: LocalDateTime): Outcome {
        targets.firstOrNull { it.mode is BlockMode.Blocked }?.let {
            return Outcome.Block(it.id, allowanceSpent = false)
        }

        val running = targets.mapNotNull { target ->
            (target.mode as? BlockMode.Allowance)?.let { target to it }
        }
        if (running.isEmpty()) return Outcome.Allow

        running.firstOrNull { (target, mode) ->
            remainingMillis(mode, target.spent, now) <= 0L
        }?.let { (target, _) -> return Outcome.Block(target.id, allowanceSpent = true) }

        return Outcome.OnTheClock(
            charge = running.map { (target, _) -> target.id },
            remainingMillis = running.minOf { (target, mode) ->
                remainingMillis(mode, target.spent, now)
            },
        )
    }

    /**
     * Adds [elapsedMillis] to a record, rolling it over if the period has turned.
     */
    fun charge(
        spent: Spent,
        period: Period,
        elapsedMillis: Long,
        now: LocalDateTime,
    ): Spent = Spent(
        periodKey = periodKey(period, now),
        millis = spentInCurrentPeriod(spent, period, now) + elapsedMillis.coerceAtLeast(0L),
    )

    /**
     * How much of the gap between two evaluations counts as time spent looking
     * at the screen.
     *
     * The service re-checks the screen every half second or so while an app is
     * in front, and the gaps between those checks are the raw material for the
     * clock. But the same field also holds the moment before the phone was
     * locked overnight, and counting that would spend a whole day's allowance
     * while the user slept. So a gap longer than [MAX_CREDITED_GAP_MS] is
     * treated as the user having been away, and contributes nothing.
     */
    fun creditableMillis(previousAtMillis: Long?, nowMillis: Long): Long {
        if (previousAtMillis == null) return 0L
        val gap = nowMillis - previousAtMillis
        if (gap <= 0L || gap > MAX_CREDITED_GAP_MS) return 0L
        return gap
    }

    const val MAX_CREDITED_GAP_MS = 5_000L
}
