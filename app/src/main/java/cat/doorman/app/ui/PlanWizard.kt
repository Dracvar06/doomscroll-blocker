package cat.doorman.app.ui

import android.content.Context
import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import cat.doorman.app.R
import cat.doorman.app.limits.HardTimes
import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.ReelQuota
import cat.doorman.app.rules.RuleSet

private const val LAST_STEP = 6
private const val QUICK_STEP = 5

/** Waits worth a button in the wizard; Settings has the rest, behind Custom. */
private val PLAN_DELAYS = listOf(0, 30, 120, 300)

/** What Android could say about the apps, for the wizard to lean on. */
data class PlanUsage(
    val granted: Boolean = false,
    /** Average foreground millis a day, by the app's main package. */
    val averages: Map<String, Long> = emptyMap(),
    val days: Int = 0,
)

/**
 * Everything the wizard has been told so far. Held outside the composable so
 * that a trip to Android's settings for usage access, which pauses the
 * activity, does not lose a single choice.
 */
class PlanWizardState(
    /** A plan has been made before, so there are weeks to look back on. */
    val rerun: Boolean,
    /** The limits in force now, which the parts start from. */
    val existing: Map<String, Limits>,
    delaySeconds: Int,
    weeklyReport: Boolean,
    /** The apps picked when the wizard opens: those that already have limits. */
    private val preselected: Set<String> = emptySet(),
) {
    var step by mutableIntStateOf(1)
    var done by mutableStateOf(false)
    var chosen by mutableStateOf(preselected)
    var estimating by mutableStateOf(false)
    val estimates = mutableStateMapOf<String, Int>()
    val reductions = mutableStateMapOf<String, Reduction>()
    val customs = mutableStateMapOf<String, Int>()
    /** What was picked for each part, by screen id; a part not picked yet starts from [existing]. */
    val parts = mutableStateMapOf<String, PartChoice>()
    var quick by mutableStateOf(false)
    var stepByStep by mutableStateOf(false)
    var pace by mutableStateOf(Pace.STEADY)
    var delaySeconds by mutableIntStateOf(delaySeconds)
    var weeklyReport by mutableStateOf(weeklyReport)
    var eveningGrey by mutableStateOf(false)

    /** Whether leaving would throw something away: a preselection alone is not an answer. */
    val touched: Boolean get() = chosen != preselected

    fun back(): Boolean {
        if (step <= 1) return false
        step = if (step == QUICK_STEP && quick) 1 else step - 1
        return true
    }
}

/** The apps the wizard offers: those with rules that are on this phone, longest use first. */
private fun offeredApps(
    rules: RuleSet,
    installed: Set<String>,
    usage: PlanUsage,
    label: (String) -> String,
): List<String> = rules.apps.filter { (pkg, app) -> app.packages(pkg).any { it in installed } }
    .keys
    .sortedWith(
        compareByDescending<String> { usage.averages[it] ?: 0L }
            .thenBy { label(it).lowercase() },
    )

/**
 * Where an app's usual minutes come from: Android's figure when it has one and
 * the person has not chosen to estimate, else what they said.
 */
private fun PlanWizardState.usualFor(pkg: String, usage: PlanUsage): Int {
    val measured = usage.averages[pkg]?.takeIf { it > 0L }
    return if (usage.granted && !estimating && measured != null) {
        PlanMath.usualMinutes(measured)
    } else {
        estimates[pkg] ?: PlanMath.DEFAULT_ESTIMATE
    }
}

private fun PlanWizardState.choiceFor(pkg: String, rules: RuleSet, usage: PlanUsage): AppChoice {
    val usual = usualFor(pkg, usage)
    val app = rules.apps.getValue(pkg)
    return AppChoice(
        packageName = pkg,
        usualMinutes = usual,
        reduction = reductions[pkg] ?: Reduction.HALF,
        customMinutes = customs[pkg] ?: PlanMath.suggestTarget(usual, Reduction.HALF),
        parts = PlanMath.currentParts(app, pkg, existing) + parts.filterKeys { id -> app.screens.any { it.id == id } },
    )
}

private fun PlanWizardState.plan(order: List<String>, rules: RuleSet, usage: PlanUsage): Plan =
    Plan(
        apps = order.filter { it in chosen }.map { choiceFor(it, rules, usage) },
        quick = quick,
        pace = if (stepByStep) pace else null,
    )

/**
 * The parts a plan does something about, grouped under what is done with them
 * ("Blocked: Reels, Explore."). A label over a list never has to agree in
 * gender or number with the names under it, which a word after each name does.
 */
private fun partGroups(res: Resources, parts: List<Pair<String, PartChoice>>): String = buildList {
    val blocked = parts.filter { it.second == PartChoice.Blocked }.map { it.first }
    val free = parts.filter { it.second == PartChoice.Free }.map { it.first }
    val videos = parts.mapNotNull { (name, choice) ->
        (choice as? PartChoice.Videos)?.let { res.getString(R.string.plan_part_videos_item, name, reelsText(res, it.quota)) }
    }
    if (blocked.isNotEmpty()) add(res.getString(R.string.plan_parts_blocked, blocked.joinToString(", ")))
    if (free.isNotEmpty()) add(res.getString(R.string.plan_parts_free, free.joinToString(", ")))
    if (videos.isNotEmpty()) add(res.getString(R.string.plan_parts_videos, videos.joinToString(", ")))
}.joinToString(" ")

/**
 * One line of the plan as a sentence; [app] is the app's name and [partName]
 * the name of one of its parts.
 */
internal fun planLineText(
    res: Resources,
    context: Context,
    line: PlanLine,
    app: String,
    partName: (String) -> String,
): String = when (line) {
    is PlanLine.Budget -> {
        // The limit starts at fromMinutes, which is not what the app takes now.
        val time = if (line.lowers && line.reachesOn != null) {
            res.getString(
                R.string.plan_line_taper,
                app,
                durationText(res, line.fromMinutes),
                durationText(res, line.usualMinutes),
                durationText(res, line.toMinutes),
                dateText(context, line.reachesOn),
            )
        } else {
            res.getString(
                R.string.plan_line_flat,
                app,
                durationText(res, line.fromMinutes),
                durationText(res, line.usualMinutes),
            )
        }
        val messages = if (line.messagesFree) res.getString(R.string.plan_line_messages) else ""
        val parts = partGroups(res, line.parts.map { partName(it.screenId) to it.choice })
        listOf(time, messages, parts).filter { it.isNotEmpty() }.joinToString(" ")
    }
    is PlanLine.Blocked -> res.getString(R.string.plan_line_blocked, app)
}

/** "A, B and C" the way the phone's language says it. */
private fun listText(res: Resources, items: List<String>): String =
    android.icu.text.ListFormatter.getInstance(res.configuration.locales[0]).format(items)

/** The wait as words, outside a composable. */
internal fun delayText(res: Resources, seconds: Int): String = when {
    seconds == 0 -> res.getString(R.string.change_delay_instant)
    seconds < 60 -> res.getString(R.string.change_delay_seconds, seconds)
    seconds % 60 == 0 -> res.getString(R.string.change_delay_minutes, seconds / 60)
    else -> res.getString(R.string.change_delay_minutes_seconds, seconds / 60, seconds % 60)
}

/**
 * The guided way to set limits: which apps, how much time now, where to get
 * to, how fast, how to hold to it, and a plain summary before anything is
 * written. Nothing is saved until the last button; leaving at any point costs
 * nothing but the answers.
 */
@Composable
fun PlanWizard(
    state: PlanWizardState,
    rules: RuleSet,
    icons: AppIcons,
    installedPackages: Set<String>,
    usage: PlanUsage,
    lookBack: LookBack?,
    hard: HardTimes,
    grayCanWrite: Boolean,
    onAllowUsage: () -> Unit,
    onStart: (Plan, PlanExtras) -> Unit,
    onShare: (String) -> Unit,
    onClose: () -> Unit,
    labelFor: (String) -> String,
    briefLabelFor: (String) -> String,
) {
    val res = LocalResources.current
    val context = LocalContext.current
    var leaving by remember { mutableStateOf(false) }
    val appName = { pkg: String -> labelFor(rules.apps[pkg]?.labelKey ?: "") }
    val order = remember(rules, installedPackages, usage.averages) {
        offeredApps(rules, installedPackages, usage, appName)
    }
    // Back is one step at a time, and from the first step asks before throwing
    // answers away. The finished screen has nothing left to lose.
    val goBack = {
        when {
            state.done -> onClose()
            state.back() -> Unit
            state.touched -> leaving = true
            else -> onClose()
        }
    }
    BackHandler(onBack = goBack)

    // Back from Android's settings with the permission given: the figures are
    // there to be used, and an earlier "I will estimate" no longer applies.
    LaunchedEffect(usage.granted) { if (usage.granted) state.estimating = false }

    if (state.done) {
        PlanDone(
            onShare = {
                val plan = state.plan(order, rules, usage)
                onShare(shareText(plan, rules, hard, appName, briefLabelFor, res, context, java.time.LocalDate.now()))
            },
            onClose = onClose,
        )
    } else {
        val title = stringResource(stepTitle(state.step))
        SubPageHeader(title = title, onBack = goBack)
        Text(
            stringResource(R.string.plan_step_of, state.step, LAST_STEP),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (state.step) {
            1 -> ChooseApps(state, icons, order, usage, appName)
            2 -> TimeNow(state, rules, icons, order, usage, lookBack, appName, onAllowUsage)
            3 -> Targets(state, rules, icons, order, usage, appName, briefLabelFor)
            4 -> HowFast(state, rules, order, usage, hard, appName)
            5 -> HoldYourself(state, grayCanWrite)
            else -> YourPlan(
                state = state,
                rules = rules,
                order = order,
                usage = usage,
                hard = hard,
                grayCanWrite = grayCanWrite,
                appName = appName,
                briefLabelFor = briefLabelFor,
                onStart = onStart,
                onShare = onShare,
            )
        }
    }

    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.plan_leave_title)) },
            text = { Text(stringResource(R.string.plan_leave_body)) },
            confirmButton = {
                TextButton(onClick = { leaving = false; onClose() }) {
                    Text(stringResource(R.string.plan_leave))
                }
            },
            dismissButton = {
                TextButton(onClick = { leaving = false }) {
                    Text(stringResource(R.string.plan_stay))
                }
            },
        )
    }
}

private fun stepTitle(step: Int): Int = when (step) {
    1 -> R.string.plan_title_apps
    2 -> R.string.plan_title_now
    3 -> R.string.plan_title_goal
    4 -> R.string.plan_title_pace
    5 -> R.string.plan_title_hold
    else -> R.string.plan_title_plan
}

/** The Next button, and the line saying what is missing when it cannot be pressed. */
@Composable
private fun NextButton(enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.plan_next))
    }
}

@Composable
private fun AppIcon(icons: AppIcons, pkg: String, label: String) {
    val icon by produceState<ImageBitmap?>(null, pkg) { value = icons.load(pkg) }
    AppAvatar(icon = icon, label = label, size = 40.dp, loading = icon == null)
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// Step 1

@Composable
private fun ChooseApps(
    state: PlanWizardState,
    icons: AppIcons,
    order: List<String>,
    usage: PlanUsage,
    appName: (String) -> String,
) {
    val res = LocalResources.current
    Hint(stringResource(R.string.plan_apps_intro))
    if (order.isEmpty()) Hint(stringResource(R.string.plan_apps_none))
    order.forEach { pkg ->
        val name = appName(pkg)
        val picked = pkg in state.chosen
        val measured = usage.averages[pkg]?.takeIf { usage.granted && it > 0L }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .toggleable(
                    value = picked,
                    role = Role.Checkbox,
                    onValueChange = { on -> state.chosen = if (on) state.chosen + pkg else state.chosen - pkg },
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(icons, pkg, name)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                if (measured != null) {
                    Hint(
                        res.getString(
                            R.string.mode_allowance_day,
                            durationText(res, PlanMath.usualMinutes(measured)),
                        ),
                    )
                }
            }
            Checkbox(checked = picked, onCheckedChange = null)
        }
    }
    NextButton(enabled = state.chosen.isNotEmpty()) {
        state.quick = false
        state.step = 2
    }
    // The shortcut: no questions, only what never ends.
    OutlinedButton(
        enabled = state.chosen.isNotEmpty(),
        onClick = {
            state.quick = true
            state.step = QUICK_STEP
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.plan_quick))
    }
    Hint(stringResource(R.string.plan_quick_hint))
}

// Step 2

@Composable
private fun TimeNow(
    state: PlanWizardState,
    rules: RuleSet,
    icons: AppIcons,
    order: List<String>,
    usage: PlanUsage,
    lookBack: LookBack?,
    appName: (String) -> String,
    onAllowUsage: () -> Unit,
) {
    val res = LocalResources.current
    if (state.rerun) {
        SurfaceCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.plan_lookback_title), style = MaterialTheme.typography.titleMedium)
                if (lookBack == null) {
                    Hint(stringResource(R.string.plan_lookback_none))
                } else {
                    lookBack.apps.filter { it.packageName in state.chosen }.forEach { app ->
                        val name = appName(app.packageName)
                        val average = durationText(res, app.averageMinutes)
                        Text(
                            if (app.limitMinutes == null) {
                                stringResource(R.string.plan_lookback_app, name, average)
                            } else {
                                stringResource(
                                    R.string.plan_lookback_app_limit,
                                    name,
                                    average,
                                    durationText(res, app.limitMinutes),
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (app.daysRanOut > 0) {
                            Hint(pluralStringResource(R.plurals.plan_lookback_ran_out, app.daysRanOut, app.daysRanOut))
                        }
                        when (app.suggestion) {
                            Suggestion.KEEP_OR_EASE -> Hint(stringResource(R.string.plan_suggest_keep))
                            Suggestion.LOWER -> Hint(stringResource(R.string.plan_suggest_lower))
                            Suggestion.NONE -> Unit
                        }
                    }
                    Hint(stringResource(R.string.plan_lookback_totals, lookBack.hardDays, lookBack.loosenings))
                }
            }
        }
    }

    if (!usage.granted && !state.estimating) {
        SurfaceCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.plan_usage_title), style = MaterialTheme.typography.titleMedium)
                Hint(stringResource(R.string.plan_usage_body))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAllowUsage) { Text(stringResource(R.string.plan_usage_allow)) }
                    TextButton(onClick = { state.estimating = true }) {
                        Text(stringResource(R.string.plan_usage_estimate))
                    }
                }
            }
        }
    } else {
        order.filter { it in state.chosen }.forEach { pkg ->
            val name = appName(pkg)
            val measured = usage.averages[pkg]?.takeIf { usage.granted && !state.estimating && it > 0L }
            SurfaceCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(icons, pkg, name)
                        Spacer(Modifier.width(12.dp))
                        Text(name, style = MaterialTheme.typography.titleMedium)
                    }
                    if (measured != null) {
                        Text(
                            stringResource(
                                R.string.plan_usage_average,
                                durationText(res, PlanMath.usualMinutes(measured)),
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Hint(pluralStringResource(R.plurals.plan_usage_days, usage.days, usage.days))
                        // Said here once; the next step only says what counts.
                        if (PlanMath.messageScreens(rules.apps.getValue(pkg)).isNotEmpty()) {
                            Hint(stringResource(R.string.plan_usage_messages))
                        }
                    } else {
                        val minutes = state.estimates[pkg] ?: PlanMath.DEFAULT_ESTIMATE
                        val said = durationText(res, minutes)
                        Text(
                            stringResource(R.string.plan_estimate_label, said),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        val description = stringResource(R.string.plan_estimate_slider, name)
                        Slider(
                            value = minutes.toFloat(),
                            onValueChange = { state.estimates[pkg] = it.toInt() },
                            valueRange = PlanMath.MIN_ESTIMATE.toFloat()..PlanMath.MAX_ESTIMATE.toFloat(),
                            steps = (PlanMath.MAX_ESTIMATE - PlanMath.MIN_ESTIMATE) / PlanMath.ESTIMATE_STEP - 1,
                            modifier = Modifier.semantics {
                                contentDescription = description
                                stateDescription = said
                            },
                        )
                    }
                }
            }
        }
    }
    NextButton(enabled = true) { state.step = 3 }
}

// Step 3

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Targets(
    state: PlanWizardState,
    rules: RuleSet,
    icons: AppIcons,
    order: List<String>,
    usage: PlanUsage,
    appName: (String) -> String,
    briefLabelFor: (String) -> String,
) {
    val res = LocalResources.current
    val chosen = order.filter { it in state.chosen }
    chosen.forEach { pkg ->
        val app = rules.apps.getValue(pkg)
        val name = appName(pkg)
        val choice = state.choiceFor(pkg, rules, usage)
        val parts = PlanMath.partScreens(app)
        var partsOpen by remember(pkg) { mutableStateOf(false) }
        SurfaceCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(icons, pkg, name)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Hint(stringResource(R.string.plan_goal_now, durationText(res, choice.usualMinutes)))
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlanMath.targetChips(choice.usualMinutes, choice.reduction).forEach { reduction ->
                        val minutes = PlanMath.suggestTarget(choice.usualMinutes, reduction, choice.customMinutes)
                        FilterChip(
                            selected = choice.reduction == reduction,
                            onClick = { state.reductions[pkg] = reduction },
                            label = {
                                // The minutes are the second line only where the label is not itself the minutes.
                                Column {
                                    Text(
                                        when (reduction) {
                                            Reduction.A_BIT -> stringResource(R.string.plan_goal_bit, 25)
                                            Reduction.HALF -> stringResource(R.string.plan_goal_half)
                                            Reduction.THIRTY -> dialLabel(minutes)
                                            Reduction.CUSTOM -> stringResource(R.string.plan_goal_custom)
                                        },
                                    )
                                    if (reduction == Reduction.A_BIT || reduction == Reduction.HALF) {
                                        Text(dialLabel(minutes), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            },
                        )
                    }
                }
                if (choice.reduction == Reduction.CUSTOM) {
                    val custom = choice.customMinutes
                    Stepper(
                        label = stringResource(R.string.plan_goal_label),
                        value = dialLabel(custom),
                        lessDescription = stringResource(R.string.taper_goal_lower),
                        moreDescription = stringResource(R.string.taper_goal_higher),
                        canLess = custom > PlanMath.MIN_TARGET,
                        canMore = custom < PlanMath.MAX_TARGET,
                        onLess = { state.customs[pkg] = (custom - PlanMath.STEP_MINUTES).coerceAtLeast(PlanMath.MIN_TARGET) },
                        onMore = { state.customs[pkg] = (custom + PlanMath.STEP_MINUTES).coerceAtMost(PlanMath.MAX_TARGET) },
                        endPadding = 0.dp,
                    )
                }
                Text(
                    stringResource(
                        R.string.plan_goal_aim,
                        stringResource(R.string.mode_allowance_day, durationText(res, choice.targetMinutes)),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                val named = { id: String -> briefLabelFor(parts.first { it.id == id }.labelKey) }
                val left = buildList {
                    if (PlanMath.messageScreens(app).isNotEmpty()) add(stringResource(R.string.plan_item_messages))
                    parts.filter { choice.parts[it.id] == PartChoice.Free }.forEach { add(named(it.id)) }
                }
                Text(
                    if (left.isEmpty()) {
                        stringResource(R.string.plan_goal_counts, name)
                    } else {
                        stringResource(R.string.plan_goal_counts_except, name, listText(res, left))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                val blocked = parts.filter { choice.parts[it.id] == PartChoice.Blocked }.map { named(it.id) }
                if (blocked.isNotEmpty()) {
                    Text(
                        stringResource(R.string.plan_goal_blocked, blocked.joinToString(", ")),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                // The parts, closed until asked for: most people want them to
                // count as they are.
                val summary = partGroups(
                    res,
                    parts.mapNotNull { screen -> choice.parts[screen.id]?.let { briefLabelFor(screen.labelKey) to it } },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(
                            onClickLabel = stringResource(if (partsOpen) R.string.card_close else R.string.card_open),
                            role = Role.Button,
                        ) { partsOpen = !partsOpen },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.plan_parts_title), style = MaterialTheme.typography.titleSmall)
                        Hint(summary.ifEmpty { stringResource(R.string.plan_parts_everything) })
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.rotate(if (partsOpen) 180f else 0f),
                    )
                }
                if (partsOpen) {
                    parts.forEach { screen ->
                        PartRow(
                            name = briefLabelFor(screen.labelKey),
                            choice = choice.parts[screen.id] ?: PartChoice.Counts,
                            countsVideos = screen.countsReels,
                            onChoice = { state.parts[screen.id] = it },
                        )
                    }
                    if (parts.any { it.countsReels || it.endless }) Hint(stringResource(R.string.plan_parts_hint))
                }
            }
        }
    }
    NextButton(enabled = true) { state.step = 4 }
}

/**
 * One part of an app and what is done with it, as a single row that opens a
 * menu: four labels side by side do not fit in Catalan at a larger text size.
 * Read out as one control, the part and its choice together.
 */
@Composable
private fun PartRow(name: String, choice: PartChoice, countsVideos: Boolean, onChoice: (PartChoice) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = buildList {
        add(PartChoice.Counts)
        add(PartChoice.Blocked)
        add(PartChoice.Free)
        if (countsVideos) add(PartChoice.Videos(ReelQuota.DEFAULT))
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    onClickLabel = stringResource(R.string.plan_part_choose),
                    role = Role.DropdownList,
                ) { open = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            // The menu is anchored to its own control, not to the row's start.
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(choiceLabel(choice)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    options.forEach { option ->
                        val selected = option::class == choice::class
                        DropdownMenuItem(
                            text = { Text(stringResource(choiceLabel(option))) },
                            onClick = {
                                open = false
                                if (!selected) onChoice(option)
                            },
                            modifier = Modifier.semantics { this.selected = selected },
                        )
                    }
                }
            }
        }
        if (choice is PartChoice.Videos) {
            ReelQuotaSteppers(choice.quota, { onChoice(PartChoice.Videos(it)) }, endPadding = 0.dp)
        }
    }
}

private fun choiceLabel(choice: PartChoice): Int = when (choice) {
    PartChoice.Counts -> R.string.plan_choice_counts
    PartChoice.Blocked -> R.string.plan_choice_blocked
    PartChoice.Free -> R.string.plan_choice_free
    is PartChoice.Videos -> R.string.plan_choice_videos
}

// Step 4

@Composable
private fun HowFast(
    state: PlanWizardState,
    rules: RuleSet,
    order: List<String>,
    usage: PlanUsage,
    hard: HardTimes,
    appName: (String) -> String,
) {
    val res = LocalResources.current
    val context = LocalContext.current
    val today = remember { java.time.LocalDate.now() }
    val choices = order.filter { it in state.chosen }.map { state.choiceFor(it, rules, usage) }
    // Only the apps that have somewhere to come down from have a pace.
    val lowering = choices.filter {
        PlanMath.startLevel(it.usualMinutes, it.targetMinutes) > it.targetMinutes
    }

    PaceOption(
        selected = !state.stepByStep,
        title = stringResource(R.string.plan_pace_now),
        body = stringResource(R.string.plan_pace_now_body),
        onClick = { state.stepByStep = false },
    )
    PaceOption(
        selected = state.stepByStep,
        title = stringResource(R.string.plan_pace_steps),
        body = stringResource(R.string.plan_pace_steps_body),
        onClick = { state.stepByStep = true },
    )
    if (state.stepByStep) {
        if (lowering.isEmpty()) Hint(stringResource(R.string.plan_pace_nothing))
        if (lowering.isNotEmpty()) {
            Text(stringResource(R.string.taper_pace_label), style = MaterialTheme.typography.titleSmall)
            Pace.entries.forEach { pace ->
                PaceOption(
                    selected = state.pace == pace,
                    title = stringResource(pace.label),
                    body = lowering.joinToString("\n") { choice ->
                        val start = PlanMath.startLevel(choice.usualMinutes, choice.targetMinutes)
                        val step = PlanMath.paceSteps(start, choice.targetMinutes, hard, today).first { it.pace == pace }
                        val pace = res.getString(
                            R.string.plan_pace_line,
                            appName(choice.packageName),
                            res.getString(R.string.mode_allowance_week, durationText(res, step.stepMinutes)),
                        )
                        // The date is the earliest, said once per app.
                        val reaches = step.reachesOn?.let {
                            res.getString(
                                R.string.plan_pace_reaches,
                                durationText(res, choice.targetMinutes),
                                dateText(context, it),
                            )
                        }
                        listOfNotNull(pace, reaches).joinToString(" ")
                    },
                    onClick = { state.pace = pace },
                )
            }
        }
    }
    NextButton(enabled = true) { state.step = 5 }
}

@Composable
private fun PaceOption(selected: Boolean, title: String, body: String, onClick: () -> Unit) {
    SurfaceCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .heightIn(min = 56.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(8.dp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Hint(body)
            }
        }
    }
}

// Step 5

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HoldYourself(state: PlanWizardState, grayCanWrite: Boolean) {
    SurfaceCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.plan_wait_title), style = MaterialTheme.typography.bodyLarge)
            Hint(stringResource(R.string.plan_wait_hint))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PLAN_DELAYS.forEach { seconds ->
                    FilterChip(
                        selected = state.delaySeconds == seconds,
                        onClick = { state.delaySeconds = seconds },
                        label = { Text(delayLabel(seconds)) },
                    )
                }
            }
        }
    }
    SurfaceCard {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SwitchRow(
                label = stringResource(R.string.plan_weekly_title),
                checked = state.weeklyReport,
                onCheckedChange = { state.weeklyReport = it },
                help = stringResource(R.string.weekly_report_help),
                supporting = stringResource(R.string.plan_weekly_help),
            )
            if (grayCanWrite) {
                SwitchRow(
                    label = stringResource(R.string.plan_gray_switch),
                    checked = state.eveningGrey,
                    onCheckedChange = { state.eveningGrey = it },
                    help = stringResource(R.string.plan_gray_help),
                )
            } else {
                Hint(stringResource(R.string.plan_gray_unavailable))
            }
        }
    }
    Hint(stringResource(R.string.plan_hard_note))
    NextButton(enabled = true) { state.step = LAST_STEP }
}

// Step 6

@Composable
private fun YourPlan(
    state: PlanWizardState,
    rules: RuleSet,
    order: List<String>,
    usage: PlanUsage,
    hard: HardTimes,
    grayCanWrite: Boolean,
    appName: (String) -> String,
    briefLabelFor: (String) -> String,
    onStart: (Plan, PlanExtras) -> Unit,
    onShare: (String) -> Unit,
) {
    val res = LocalResources.current
    val context = LocalContext.current
    val today = remember { java.time.LocalDate.now() }
    val plan = state.plan(order, rules, usage)
    val lines = PlanMath.summary(plan, rules, hard, today)
    SurfaceCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            lines.forEach { line ->
                Text(
                    planLineText(res, context, line, appName(line.packageName), partNames(rules, briefLabelFor)),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (state.stepByStep) {
                lines.filterIsInstance<PlanLine.Budget>().filter { it.lowers }.forEach { line ->
                    val step = state.pace.stepFor(line.fromMinutes)
                    Hint(
                        stringResource(
                            R.string.plan_summary_pace,
                            appName(line.packageName),
                            durationText(res, step),
                        ),
                    )
                }
            }
            if (state.delaySeconds > 0) Hint(stringResource(R.string.plan_summary_wait, delayText(res, state.delaySeconds)))
            if (state.weeklyReport) Hint(stringResource(R.string.plan_summary_report))
            if (state.eveningGrey && grayCanWrite) Hint(stringResource(R.string.plan_summary_gray))
        }
    }
    Button(
        onClick = {
            onStart(
                plan,
                PlanExtras(
                    delaySeconds = state.delaySeconds,
                    weeklyReport = state.weeklyReport,
                    eveningGrey = state.eveningGrey && grayCanWrite,
                ),
            )
            state.done = true
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.plan_start))
    }
    OutlinedButton(
        onClick = { onShare(shareText(plan, rules, hard, appName, briefLabelFor, res, context, today)) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.plan_share))
    }
}

/** A part's name wherever it is told, from its screen id. */
private fun partNames(rules: RuleSet, briefLabelFor: (String) -> String): (String) -> String = { id ->
    briefLabelFor(rules.apps.values.firstNotNullOfOrNull { app -> app.screens.firstOrNull { it.id == id } }?.labelKey ?: "")
}

/** The message people send, from the same lines the plan page shows. */
internal fun shareText(
    plan: Plan,
    rules: RuleSet,
    hard: HardTimes,
    appName: (String) -> String,
    briefLabelFor: (String) -> String,
    res: Resources,
    context: Context,
    today: java.time.LocalDate,
): String = PlanMath.shareText(
    intro = res.getString(R.string.plan_share_intro),
    lines = PlanMath.summary(plan, rules, hard, today).map {
        planLineText(res, context, it, appName(it.packageName), partNames(rules, briefLabelFor))
    },
    outro = res.getString(R.string.plan_share_outro),
)

@Composable
private fun PlanDone(onShare: () -> Unit, onClose: () -> Unit) {
    Text(
        stringResource(R.string.plan_done_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    Hint(stringResource(R.string.plan_done_body))
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.plan_done_button))
    }
    OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.plan_share))
    }
}
