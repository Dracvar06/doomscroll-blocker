package cat.doorman.app.limits

import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Keeps the books on time already spent, and decides what the limits on a
 * screen mean right now.
 *
 * Pure: no clock of its own, no storage, no Android. The awkward parts are the
 * turn of the hour, several limits running at once, a window crossing midnight,
 * and the moment the last second is spent -- and none of those should need a
 * phone and a stopwatch to test.
 */
object Allowances {

    /** Time spent against one target in one kind of period. */
    data class Spent(val periodKey: String, val millis: Long) {
        companion object {
            val NOTHING = Spent("", 0L)
        }
    }

    /**
     * A budget is per target *and* per period. "Five minutes an hour" and
     * "thirty minutes a day" on the same screen are two counters, and sharing
     * one between them would spend both at once.
     */
    fun ledgerKey(targetId: String, period: Period): String = "$targetId|$period"

    /**
     * Names the stretch of time an allowance is measured over.
     *
     * Calendar periods, not rolling windows: someone should be able to say when
     * their next five minutes arrive without doing arithmetic. The cost is that
     * an allowance spent at the end of one hour is followed straight away by the
     * next hour's, which is a real hole and a deliberate trade.
     *
     * A week is named by the Monday it starts on, which sidesteps the question
     * of what week number the days around New Year belong to.
     */
    fun periodKey(period: Period, now: LocalDateTime): String = when (period) {
        Period.DAY -> now.toLocalDate().toString()
        Period.HOUR -> "${now.toLocalDate()}T${now.hour}"
        Period.WEEK -> "W${now.toLocalDate().minusDays((now.dayOfWeek.value - 1).toLong())}"
    }

    fun spentInCurrentPeriod(spent: Spent, period: Period, now: LocalDateTime): Long =
        if (spent.periodKey == periodKey(period, now)) spent.millis else 0L

    /**
     * What is left of [allowance]. Worth what it is on the day of [now], which
     * is not always its starting minutes: see [Tapers.effectiveMinutes].
     */
    fun remainingMillis(
        allowance: Allowance,
        spent: Spent,
        now: LocalDateTime,
        hard: HardTimes = HardTimes.EMPTY,
    ): Long =
        (Tapers.effectiveMinutes(allowance, hard, now.toLocalDate()) * 60_000L -
            spentInCurrentPeriod(spent, allowance.period, now))
            .coerceAtLeast(0L)

    /**
     * One thing being held, with whatever has been spent against it.
     *
     * [id] is a screen id or a package name. The two share a namespace, which is
     * safe because package names contain dots and screen ids do not.
     */
    data class Target(
        val id: String,
        val limits: Limits,
        val spent: Map<String, Spent> = emptyMap(),
    ) {
        fun spentFor(period: Period): Spent = spent[ledgerKey(id, period)] ?: Spent.NOTHING
    }

    enum class Reason {
        /** Held outright. */
        ALWAYS,

        /** Held at this time of day. */
        SCHEDULE,

        /** The minutes for this period are gone. */
        ALLOWANCE_SPENT,
    }

    sealed interface Outcome {
        data object Allow : Outcome

        data class Block(val targetId: String, val reason: Reason) : Outcome

        /**
         * Watchable, but the clock is running on [charge] -- ledger keys that
         * time should be counted against. [remainingMillis] is how long until
         * the tightest of them runs out.
         */
        data class OnTheClock(
            val charge: List<String>,
            val remainingMillis: Long,
        ) : Outcome
    }

    /**
     * What to do about a screen, given every target that applies to it.
     *
     * More than one applies at once on purpose: limits can sit on Instagram as
     * a whole and on Reels within it, and the answer someone expects is that
     * whichever runs out first stops them. Held outright wins, then the clock,
     * then a spent allowance, and otherwise the clock runs on everything at
     * once.
     */
    fun decide(
        targets: List<Target>,
        now: LocalDateTime,
        hard: HardTimes = HardTimes.EMPTY,
    ): Outcome {
        val day = now.dayOfWeek
        val minuteOfDay = now.hour * 60 + now.minute

        targets.firstOrNull { it.limits.blocked }?.let {
            return Outcome.Block(it.id, Reason.ALWAYS)
        }
        targets.firstOrNull { it.limits.windowAt(day, minuteOfDay) != null }?.let {
            return Outcome.Block(it.id, Reason.SCHEDULE)
        }

        val running = targets.flatMap { target ->
            target.limits.allowancesOn(day).map { target to it }
        }
        if (running.isEmpty()) return Outcome.Allow

        running.firstOrNull { (target, allowance) ->
            remainingMillis(allowance, target.spentFor(allowance.period), now, hard) <= 0L
        }?.let { (target, _) -> return Outcome.Block(target.id, Reason.ALLOWANCE_SPENT) }

        return Outcome.OnTheClock(
            charge = running.map { (target, allowance) ->
                ledgerKey(target.id, allowance.period)
            }.distinct(),
            remainingMillis = running.minOf { (target, allowance) ->
                remainingMillis(allowance, target.spentFor(allowance.period), now, hard)
            },
        )
    }

    /**
     * The ledger keys of every allowance running on [targets] on [day], whether
     * or not it has been spent. For time that is counted but not enforced.
     */
    fun chargeKeys(targets: List<Target>, day: DayOfWeek): List<String> =
        targets.flatMap { target ->
            target.limits.allowancesOn(day).map { ledgerKey(target.id, it.period) }
        }.distinct()

    /** Whether any of [limits] depends on the time of day or on minutes spent. */
    fun hasTimeLimit(limits: Collection<Limits>): Boolean =
        limits.any { it.allowances.isNotEmpty() || it.windows.isNotEmpty() }

    /**
     * [ledger] with [pending] time counted in, as it will stand once written.
     * Pending is keyed by "target|PERIOD", because a screen can be on an hourly
     * and a daily budget at once and those are separate counters. Deciding on
     * the written ledger alone let a limit run over by however long the time
     * waited to be written.
     */
    fun withPending(
        ledger: Map<String, Spent>,
        pending: Map<String, Long>,
        now: LocalDateTime,
    ): Map<String, Spent> {
        if (pending.isEmpty()) return ledger
        return ledger + pending.mapNotNull { (key, millis) ->
            val period = runCatching { Period.valueOf(key.substringAfterLast('|')) }
                .getOrNull() ?: return@mapNotNull null
            key to charge(ledger[key] ?: Spent.NOTHING, period, millis, now)
        }
    }

    /**
     * [disk] with [deltas] added to the entries they name. The write path for
     * the ledger: adding to what is stored, never replacing it with a total
     * worked out elsewhere, so time added meanwhile is not overwritten.
     */
    fun applyDeltas(
        disk: Map<String, Spent>,
        deltas: Map<String, Long>,
        now: LocalDateTime,
    ): Map<String, Spent> = withPending(disk, deltas, now)

    /**
     * What memory should hold after the stored ledger changed to [disk].
     *
     * Memory is the one that has seen every flush; an emission can describe a
     * moment before the latest one. So within the current period the larger
     * total wins and time is never taken back. A record from an old period
     * counts for nothing, so the one from the current period wins whichever
     * side it is on.
     */
    fun merge(
        memory: Map<String, Spent>,
        disk: Map<String, Spent>,
        now: LocalDateTime,
    ): Map<String, Spent> {
        val merged = memory.toMutableMap()
        disk.forEach { (key, stored) ->
            val held = memory[key]
            val period = runCatching { Period.valueOf(key.substringAfterLast('|')) }.getOrNull()
            merged[key] = when {
                held == null || period == null -> stored
                held.periodKey == stored.periodKey -> if (stored.millis > held.millis) stored else held
                held.periodKey == periodKey(period, now) -> held
                else -> stored
            }
        }
        return merged
    }

    /** Adds [elapsedMillis] to a record, rolling it over if the period turned. */
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
     * The service re-checks every second or so while an app is in front, and
     * those gaps are the raw material for the clock. But the same field also
     * holds the moment before the phone was locked overnight, and counting that
     * would spend a whole day's allowance while the user slept. So a gap longer
     * than [MAX_CREDITED_GAP_MS] is treated as time away and contributes
     * nothing -- unless both ends of it were on the same limited target
     * ([sameTarget]), where the user was there the whole time and the main
     * thread was merely stalled. That much is credited, no more: the stall is
     * not known to have been all watching.
     */
    fun creditableMillis(previousAtMillis: Long?, nowMillis: Long, sameTarget: Boolean = false): Long {
        if (previousAtMillis == null) return 0L
        val gap = nowMillis - previousAtMillis
        if (gap <= 0L) return 0L
        if (gap > MAX_CREDITED_GAP_MS) return if (sameTarget) MAX_CREDITED_GAP_MS else 0L
        return gap
    }

    const val MAX_CREDITED_GAP_MS = 5_000L

    /**
     * When the next window opens after [now], for telling someone how long a
     * scheduled block lasts. Null when nothing is scheduled or it never lifts.
     */
    fun blockedUntil(limits: Limits, now: LocalDateTime): LocalDateTime? {
        if (limits.blocked) return null
        val week = limits.blockedMinutesOfWeek()
        var index = ((now.dayOfWeek.value - 1) * MINUTES_PER_DAY) + now.hour * 60 + now.minute
        if (!week[index]) return null
        var stepped = 0
        while (week[index] && stepped < MINUTES_PER_WEEK) {
            index = (index + 1) % MINUTES_PER_WEEK
            stepped++
        }
        if (stepped >= MINUTES_PER_WEEK) return null
        return now.withSecond(0).withNano(0).plusMinutes(stepped.toLong())
    }
}
