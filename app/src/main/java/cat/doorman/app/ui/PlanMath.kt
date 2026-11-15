package cat.doorman.app.ui

import cat.doorman.app.gray.GrayPolicy
import cat.doorman.app.gray.GraySetting
import cat.doorman.app.limits.Allowance
import cat.doorman.app.limits.DayRecord
import cat.doorman.app.limits.HardTimes
import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.Period
import cat.doorman.app.limits.ReelQuota
import cat.doorman.app.limits.Taper
import cat.doorman.app.limits.Tapers
import cat.doorman.app.limits.Window
import cat.doorman.app.limits.isLoosening
import cat.doorman.app.rules.AppRules
import cat.doorman.app.rules.RuleSet
import cat.doorman.app.rules.ScreenRule
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Where the store page will be once there is one. Empty until then, and the
 * share text leaves the link out while it is: a message that ends in a dead
 * address is worse than one that ends a line early.
 */
const val STORE_URL = ""

/** How much of what is spent now a target asks to be cut by. */
enum class Reduction { A_BIT, HALF, THIRTY, CUSTOM }

/**
 * What a plan does with one part of an app, on top of the app's own time.
 * [Counts] is what a part does when nobody says anything: its minutes are
 * simply part of the app's.
 */
sealed interface PartChoice {
    data object Counts : PartChoice
    data object Blocked : PartChoice

    /** Left out of the app's time altogether, like messages. */
    data object Free : PartChoice

    /** A count of videos in a stretch of time; the minutes still count toward the app's time. */
    data class Videos(val quota: ReelQuota) : PartChoice
}

/**
 * One app in a plan: how much of it there is now, where it should get to, and
 * what is done with each of its parts.
 *
 * [usualMinutes] is a day's use, measured or estimated; the wizard does not
 * care which, and neither does the arithmetic. A part missing from [parts]
 * counts toward the time.
 */
data class AppChoice(
    val packageName: String,
    val usualMinutes: Int,
    val reduction: Reduction = Reduction.HALF,
    val customMinutes: Int = PlanMath.DEFAULT_ESTIMATE,
    val parts: Map<String, PartChoice> = emptyMap(),
) {
    val targetMinutes: Int get() = PlanMath.suggestTarget(usualMinutes, reduction, customMinutes)
}

/**
 * What the wizard has gathered. [quick] is the shortcut that only holds the
 * screens that never end; [pace] is null for "from tomorrow".
 */
data class Plan(
    val apps: List<AppChoice>,
    val quick: Boolean = false,
    val pace: Pace? = null,
)

/** The step-5 choices that are not limits. Null means "leave it as it is". */
data class PlanExtras(
    val delaySeconds: Int? = null,
    val weeklyReport: Boolean? = null,
    val eveningGrey: Boolean = false,
)

/** Everything a plan writes, ready for the one save. */
data class PlanApplication(
    val limits: Map<String, Limits>,
    val gray: Map<String, GraySetting>,
    val delaySeconds: Int?,
    val weeklyReport: Boolean?,
)

data class LimitChange(val id: String, val from: Limits, val to: Limits, val loosens: Boolean)

data class GrayChange(val id: String, val from: GraySetting?, val to: GraySetting, val loosens: Boolean)

/** A part of an app the plan does something about; the parts that simply count are left out. */
data class PartLine(val screenId: String, val choice: PartChoice)

/** What the plan says about one app, as the numbers a sentence is made of. */
sealed interface PlanLine {
    val packageName: String

    /**
     * [fromMinutes] equals [toMinutes] for a limit that does not lower, and
     * is where it starts when it does; [usualMinutes] is what the app takes now.
     * [messagesFree] says the app's messages are left out of the time.
     */
    data class Budget(
        override val packageName: String,
        val usualMinutes: Int,
        val fromMinutes: Int,
        val toMinutes: Int,
        val reachesOn: LocalDate?,
        val messagesFree: Boolean,
        val parts: List<PartLine>,
    ) : PlanLine {
        val lowers: Boolean get() = fromMinutes != toMinutes
    }

    data class Blocked(override val packageName: String) : PlanLine
}

/** One pace as it plays out for one app. */
data class PaceStep(val pace: Pace, val stepMinutes: Int, val reachesOn: LocalDate?)

enum class Suggestion { NONE, KEEP_OR_EASE, LOWER }

data class AppLookBack(
    val packageName: String,
    val averageMinutes: Int,
    val limitMinutes: Int?,
    val daysRanOut: Int,
    val suggestion: Suggestion,
)

/** How the last two weeks went, from Doorman's own journal. */
data class LookBack(
    val days: Int,
    val apps: List<AppLookBack>,
    val hardDays: Int,
    val loosenings: Int,
)

/**
 * The arithmetic of a plan, apart from anything that draws it.
 *
 * Plain Kotlin so every rule in it can be tested without a phone: a plan that
 * rounds oddly, or writes a limit to something the person did not choose, is
 * a plan that changes somebody's limits in a way they never agreed to.
 */
object PlanMath {

    /** Targets and starting levels are said in fives; "47 min" is a number nobody chose. */
    const val STEP_MINUTES = 5
    const val MIN_TARGET = 5
    const val MAX_TARGET = 240

    /** The slider for an estimate. */
    const val MIN_ESTIMATE = 15
    const val MAX_ESTIMATE = 240
    const val ESTIMATE_STEP = 15
    const val DEFAULT_ESTIMATE = 60

    /** A taper starts near the level somebody is at, not at it: a first week of nothing lowered is a wasted week. */
    private const val START_SHARE = 0.75

    /** What a first plan starts with, where nobody has chosen a wait yet. */
    const val FIRST_DELAY_SECONDS = 30

    const val LOOKBACK_DAYS = 14
    const val NUDGE_THRESHOLD = 3
    const val NUDGE_QUIET_DAYS = 14L

    /** The evening grey: nine at night until seven in the morning, every day. */
    val EVENING_GREY = GraySetting(listOf(Window(fromMinute = 21 * 60, toMinute = 7 * 60)))

    fun roundToFive(minutes: Double): Int =
        (Math.round(minutes / STEP_MINUTES) * STEP_MINUTES).toInt().coerceAtLeast(MIN_TARGET)

    /**
     * Where a limit should get to. The chips never ask for more than people
     * use now (thirty minutes for somebody on twenty would be a loosening);
     * a number typed in is theirs and is taken as it is.
     */
    fun suggestTarget(usual: Int, reduction: Reduction, custom: Int = usual): Int {
        return when (reduction) {
            Reduction.A_BIT -> roundToFive(usual * 0.75)
            Reduction.HALF -> roundToFive(usual * 0.5)
            Reduction.THIRTY -> minOf(30, roundToFive(usual.toDouble()))
            Reduction.CUSTOM -> custom.coerceIn(MIN_TARGET, MAX_TARGET)
        }
    }

    /** Three quarters of what is used, never under the target and never over what is used. */
    fun startLevel(usual: Int, target: Int): Int =
        roundToFive(usual * START_SHARE).coerceAtLeast(target).coerceAtMost(usual)

    /** Android's figure in minutes, rounded up: a quarter of an hour is not "0 min". */
    fun usualMinutes(millis: Long): Int =
        Math.ceil(millis / 60_000.0).toInt().coerceAtLeast(MIN_TARGET)

    /**
     * The apps that already have a limit of any kind, on the app or on one of
     * its screens, or grey: the ones somebody has already decided about, so a
     * plan starts with them picked. Only the apps installed here.
     */
    fun heldApps(
        rules: RuleSet,
        existing: Map<String, Limits>,
        gray: Map<String, GraySetting>,
        installed: Set<String>,
    ): Set<String> = rules.apps.filter { (pkg, app) ->
        app.packages(pkg).any { it in installed } &&
            (listOf(pkg) + app.screens.map { it.id }).any { id ->
                existing[id]?.isOff == false || gray[id] != null
            }
    }.keys

    /** The app's screens for conversations, which no plan counts or holds. */
    fun messageScreens(app: AppRules): List<String> =
        app.screens.filter { it.messages }.map { it.id }

    /**
     * The parts of an app a plan chooses for: every recognised screen but the
     * messages, and the ones that let a single video through.
     */
    fun partScreens(app: AppRules): List<ScreenRule> =
        app.screens.filter { !it.messages && it.verdict != ALLOW_ONCE }

    /**
     * Where each part stands in [existing], so a plan made again starts from
     * what is there. Parts that simply count are left out.
     *
     * An app limit from the earlier way of planning named the screens it
     * counted ([Limits.onlyOn]); the screens it left out were free.
     */
    fun currentParts(app: AppRules, pkg: String, existing: Map<String, Limits>): Map<String, PartChoice> {
        val appLimits = existing[pkg]
        val out = linkedMapOf<String, PartChoice>()
        partScreens(app).forEach { screen ->
            val own = existing[screen.id]
            val choice = when {
                own?.blocked == true -> PartChoice.Blocked
                own?.reels != null && screen.countsReels -> PartChoice.Videos(own.reels)
                appLimits?.exceptOn?.contains(screen.id) == true -> PartChoice.Free
                appLimits?.onlyOn?.contains(screen.id) == false -> PartChoice.Free
                else -> PartChoice.Counts
            }
            if (choice != PartChoice.Counts) out[screen.id] = choice
        }
        return out
    }

    /** What the choice says about [screen], with a part that cannot count videos counting its time. */
    private fun choiceFor(app: AppChoice, screen: ScreenRule): PartChoice =
        when (val choice = app.parts[screen.id] ?: PartChoice.Counts) {
            is PartChoice.Videos -> if (screen.countsReels) choice else PartChoice.Counts
            else -> choice
        }

    /**
     * The screens the app's time leaves alone: its messages and every part set
     * to Free. Null when there are none, which is the whole app.
     */
    fun exceptOn(app: AppChoice, rules: RuleSet): Set<String>? {
        val rule = rules.apps[app.packageName] ?: return null
        val free = partScreens(rule).filter { choiceFor(app, it) == PartChoice.Free }.map { it.id }
        return (messageScreens(rule) + free).toSet().takeIf { it.isNotEmpty() }
    }

    /**
     * The target chips for somebody who uses [usual] minutes, from the smallest
     * change to the largest, "Other" last. A fixed chip that is not under what
     * is used would be a loosening, so it is not offered; nor is one that
     * lands where an earlier one does, unless it is the one [selected].
     */
    fun targetChips(usual: Int, selected: Reduction): List<Reduction> {
        val seen = mutableSetOf<Int>()
        val fixed = listOf(Reduction.A_BIT, Reduction.HALF, Reduction.THIRTY)
            .filter { suggestTarget(usual, it) < usual }
            .sortedByDescending { suggestTarget(usual, it) }
            .filter { seen.add(suggestTarget(usual, it)) || it == selected }
        return fixed + Reduction.CUSTOM
    }

    /** The pace's step for a limit that starts at [start], and when it gets to [target]. */
    fun paceSteps(start: Int, target: Int, hard: HardTimes, today: LocalDate): List<PaceStep> =
        Pace.entries.map { pace ->
            val step = pace.stepFor(start)
            val allowance = Allowance(start, Period.DAY, taper = Taper(target, step, today))
            PaceStep(pace, step, Tapers.earliestGoalDate(allowance, hard, today))
        }

    /**
     * The daily allowance for [app]: the target from tomorrow, or a taper from
     * [startLevel] to it. A limit that would start at or under its target has
     * nothing to lower and is just the target.
     */
    fun allowanceFor(app: AppChoice, pace: Pace?, today: LocalDate): Allowance {
        val target = app.targetMinutes
        val start = startLevel(app.usualMinutes, target)
        if (pace == null || start <= target) return Allowance(target, Period.DAY)
        return Allowance(start, Period.DAY, taper = Taper(target, pace.stepFor(start), today))
    }

    /**
     * The limits the plan writes, by target id. Only what it covers: the apps
     * it lists, every part of them, and nothing else.
     *
     * The app limit is one time for the whole app; what each part does on top
     * of it is that part's own limit. A part that counts, or is free, is
     * switched off on its own account: a leftover block would make "45
     * minutes across the app" a promise the app breaks. [existing] lets a
     * limit held with care on an endless screen survive the quick path.
     */
    fun toLimits(
        plan: Plan,
        rules: RuleSet,
        today: LocalDate,
        existing: Map<String, Limits> = emptyMap(),
    ): Map<String, Limits> {
        val out = linkedMapOf<String, Limits>()
        plan.apps.forEach { choice ->
            val app = rules.apps[choice.packageName] ?: return@forEach
            if (plan.quick) {
                app.screens.filter { it.endless }.forEach {
                    out[it.id] = existing[it.id]?.takeUnless { limits -> limits.isOff } ?: Limits.BLOCKED
                }
                return@forEach
            }
            partScreens(app).forEach { screen ->
                out[screen.id] = when (val part = choiceFor(choice, screen)) {
                    PartChoice.Blocked -> Limits.BLOCKED
                    is PartChoice.Videos -> Limits(reels = part.quota)
                    PartChoice.Counts, PartChoice.Free -> Limits.OFF
                }
            }
            out[choice.packageName] = Limits(
                allowances = listOf(allowanceFor(choice, plan.pace, today)),
                exceptOn = exceptOn(choice, rules),
            )
        }
        return out
    }

    /** Grey on in the evening for each app in the plan, when asked for. */
    fun grayFor(plan: Plan, extras: PlanExtras): Map<String, GraySetting> =
        if (extras.eveningGrey) plan.apps.associate { it.packageName to EVENING_GREY } else emptyMap()

    fun application(
        plan: Plan,
        extras: PlanExtras,
        rules: RuleSet,
        today: LocalDate,
        existing: Map<String, Limits>,
    ) = PlanApplication(
        limits = toLimits(plan, rules, today, existing),
        gray = grayFor(plan, extras),
        delaySeconds = extras.delaySeconds,
        weeklyReport = extras.weeklyReport,
    )

    /** What would really change, each judged the way a single save would be. */
    fun limitChanges(
        wanted: Map<String, Limits>,
        current: Map<String, Limits>,
        hard: HardTimes,
        today: LocalDate,
    ): List<LimitChange> = wanted.mapNotNull { (id, to) ->
        val from = current[id] ?: Limits.OFF
        if (from == to) null else LimitChange(id, from, to, isLoosening(from, to, today, hard))
    }

    fun grayChanges(wanted: Map<String, GraySetting>, current: Map<String, GraySetting>): List<GrayChange> =
        wanted.mapNotNull { (id, to) ->
            val from = current[id]
            if (from == to) null else GrayChange(id, from, to, GrayPolicy.isLoosening(from, to))
        }

    /** The sentences' numbers, one line per app in the plan. */
    fun summary(plan: Plan, rules: RuleSet, hard: HardTimes, today: LocalDate): List<PlanLine> =
        plan.apps.mapNotNull<AppChoice, PlanLine> { choice ->
            val app = rules.apps[choice.packageName] ?: return@mapNotNull null
            if (plan.quick) return@mapNotNull PlanLine.Blocked(choice.packageName)
            val allowance = allowanceFor(choice, plan.pace, today)
            PlanLine.Budget(
                packageName = choice.packageName,
                usualMinutes = choice.usualMinutes,
                fromMinutes = allowance.minutes,
                toMinutes = allowance.taper?.goalMinutes ?: allowance.minutes,
                reachesOn = Tapers.earliestGoalDate(allowance, hard, today),
                messagesFree = messageScreens(app).isNotEmpty(),
                parts = partScreens(app).mapNotNull { screen ->
                    choiceFor(choice, screen).takeUnless { it == PartChoice.Counts }
                        ?.let { PartLine(screen.id, it) }
                },
            )
        }

    /**
     * The message for somebody who has agreed to ask how it is going. First
     * person and short; the link is left out until there is one.
     */
    fun shareText(
        intro: String,
        lines: List<String>,
        outro: String,
        storeUrl: String = STORE_URL,
    ): String = buildString {
        append(intro)
        lines.forEach { append('\n').append("- ").append(it) }
        append('\n').append(outro)
        if (storeUrl.isNotBlank()) append('\n').append(storeUrl)
    }

    private fun windowDays(today: LocalDate) = (today.minusDays(LOOKBACK_DAYS - 1L))..today

    private fun parse(row: DayRecord): LocalDate? = runCatching { LocalDate.parse(row.date) }.getOrNull()

    /**
     * How the last fortnight went for [packages], or null when there is no
     * record of it: Doorman keeps one only while the weekly report is on, and
     * a figure invented for a week it was not watching is not worth showing.
     */
    fun lookBack(
        journal: List<DayRecord>,
        packages: List<String>,
        rules: RuleSet,
        appLimits: Map<String, Limits>,
        hard: HardTimes,
        today: LocalDate,
    ): LookBack? {
        val range = windowDays(today)
        val rows = journal.filter { row -> parse(row)?.let { it in range } == true }
        if (rows.isEmpty()) return null
        val hardDays = hard.hardDays.count { it in range }
        val apps = packages.map { pkg ->
            val ids = rules.apps[pkg]?.screens?.map { it.id }.orEmpty()
            val perDay = rows.map { row -> ids.sumOf { row.screens[it] ?: 0L } }
            val average = ((perDay.sum() / rows.size) / 60_000L).toInt()
            val limit = appLimits[pkg]?.allowances
                ?.filter { it.period == Period.DAY }
                ?.minOfOrNull { Tapers.level(it, hard, today) }
            val ranOut = if (limit == null) 0 else perDay.count { it >= limit * 60_000L - RAN_OUT_SLACK_MILLIS }
            val suggestion = when {
                limit == null -> Suggestion.NONE
                ranOut * 2 > rows.size && hardDays > 0 -> Suggestion.KEEP_OR_EASE
                ranOut == 0 && average * 10 <= limit * 6 -> Suggestion.LOWER
                else -> Suggestion.NONE
            }
            AppLookBack(pkg, average, limit, ranOut, suggestion)
        }
        return LookBack(rows.size, apps, hardDays, rows.sumOf { it.loosenings })
    }

    /** The ledger stops at the limit, give or take the tick that crossed it. */
    private const val RAN_OUT_SLACK_MILLIS = 60_000L

    /**
     * Whether the quiet "your limits seem hard to keep" line is due: three
     * hard days or three weakened limits in a fortnight, and not within a
     * fortnight of having been waved away.
     */
    fun nudgeDue(
        journal: List<DayRecord>,
        hard: HardTimes,
        today: LocalDate,
        dismissedOn: LocalDate?,
    ): Boolean {
        if (dismissedOn != null && ChronoUnit.DAYS.between(dismissedOn, today) < NUDGE_QUIET_DAYS) return false
        val range = windowDays(today)
        val hardDays = hard.hardDays.count { it in range }
        val loosenings = journal.filter { row -> parse(row)?.let { it in range } == true }.sumOf { it.loosenings }
        return hardDays >= NUDGE_THRESHOLD || loosenings >= NUDGE_THRESHOLD
    }

    private const val ALLOW_ONCE = "ALLOW_ONCE"
}
