package cat.doorman.app.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import cat.doorman.app.BuildConfig
import cat.doorman.app.block.BlockOverlayController
import cat.doorman.app.data.Prefs
import cat.doorman.app.debug.TreeDumper
import cat.doorman.app.model.SnapshotCapture
import cat.doorman.app.report.WeeklyReportNotifier
import cat.doorman.app.rules.RuleLoader
import cat.doorman.app.rules.RuleSet
import cat.doorman.app.rules.ScreenEvaluator
import cat.doorman.app.rules.OverlayBounds
import cat.doorman.app.data.ScreenPreferences
import cat.doorman.app.limits.Allowances
import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.Period
import java.time.LocalDateTime
import cat.doorman.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Phase 1: the service exists, binds, and reports foreground app changes.
 *
 * It deliberately receives events from every package rather than a filtered
 * list. Knowing that the user arrived at a reel *from WhatsApp* rather than
 * *from the Reels tab* is what separates "someone sent me this" from
 * "I'm browsing", and a package filter would hide exactly that. Events from
 * packages we do not care about are dropped here, before any tree walk.
 */
class DoormanAccessibilityService : AccessibilityService() {

    private var lastPackage: String? = null

    /**
     * The active keyboard raises window-state events of its own, so typing in
     * YouTube's search box reads as youtube -> keyboard -> youtube. Left alone
     * that makes the keyboard the "previous screen" and destroys the entry
     * context Phase 5 relies on. Which package that is depends on the keyboard
     * the user has chosen, so it is read from the system rather than guessed.
     */
    private var imePackage: String? = null

    private var dumpReceiver: BroadcastReceiver? = null

    /**
     * The accessibility service and the activity share a process, so the
     * diagnostics screen can talk to the running service directly rather than
     * through a broadcast. Cleared on unbind so nothing outlives the service.
     */
    private var currentStatus: String = ""


    private lateinit var overlay: BlockOverlayController
    private var rules: RuleSet = RuleSet()
    private var enabledScreenIds: Set<String> = emptySet()
    private var screenLimits: Map<String, Limits> = emptyMap()
    private var appLimits: Map<String, Limits> = emptyMap()
    private var ledger: Map<String, Allowances.Spent> = emptyMap()

    /**
     * Counts time against whatever allowance is running.
     *
     * Driven by its own ticker rather than by accessibility events. A game is
     * often a single drawing surface that emits no events at all once it is
     * running, and a paused video emits none either; leaving the clock to be
     * advanced by events would hand out unlimited time on exactly the screens
     * an allowance is for.
     */
    private val allowanceClock = AllowanceClock { System.currentTimeMillis() }
    private val clockTick = Runnable { onClockTick() }
    private var clockRunning = false
    private var activePass: Prefs.Pass? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val handler = Handler(Looper.getMainLooper())
    private val evaluate = Runnable { evaluateCurrentScreen() }
    private val passExpiry = Runnable { evaluateCurrentScreen() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        imePackage = currentImePackage()
        overlay = BlockOverlayController(this)
        rules = RuleLoader.load(this)
        val screenDefaults = RuleLoader.screenDefaults(rules)
        enabledScreenIds = RuleLoader.defaultEnabledScreenIds(rules)

        // The user's choices must take effect the moment a switch moves, not at
        // the next restart, so both are collected for the life of the service
        // and every change re-runs the current screen through the rules.
        val prefs = Prefs(this)

        // Alarms do not survive a reboot, and the service coming back is the
        // one thing that always happens after one -- Doorman does nothing at
        // all until it does. Rebooking here costs nothing and saves asking for
        // RECEIVE_BOOT_COMPLETED. Scheduling replaces rather than stacks.
        serviceScope.launch {
            if (prefs.weeklyReport.first()) {
                WeeklyReportNotifier.schedule(this@DoormanAccessibilityService)
            }
        }
        serviceScope.launch {
            prefs.screenLimits(screenDefaults).collectLatest {
                screenLimits = it
                enabledScreenIds = ScreenPreferences.activeScreenIds(it)
                Log.i(TAG, "blocking set changed: $enabledScreenIds")
                evaluateCurrentScreen()
            }
        }
        serviceScope.launch {
            prefs.appLimits.collectLatest {
                appLimits = it
                evaluateCurrentScreen()
            }
        }
        serviceScope.launch { prefs.spent.collectLatest { ledger = it } }
        serviceScope.launch {
            prefs.activePass.collectLatest {
                activePass = it
                evaluateCurrentScreen()
                schedulePassExpiry(it)
            }
        }

        instance = this
        Log.i(TAG, "service connected; ime=$imePackage")
        registerDumpReceiver()
    }

    /**
     * Debug builds only: lets a dump be triggered over adb while another app is
     * in front, which is the only moment its tree is worth reading.
     *
     *   adb shell am broadcast -a cat.doorman.app.DUMP -p cat.doorman.app \
     *       --es label shorts [--ei delay 5]
     *
     * Gated on BuildConfig.DEBUG so release builds expose no such entry point.
     */
    private fun registerDumpReceiver() {
        if (!BuildConfig.DEBUG) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val label = intent?.getStringExtra("label")
                val delaySeconds = intent?.getIntExtra("delay", 0) ?: 0
                // The adb path keeps text: that is a developer dumping their
                // own screen deliberately. The in-app path redacts.
                if (delaySeconds > 0) {
                    Log.i(TAG, "dump scheduled in ${delaySeconds}s")
                    Handler(Looper.getMainLooper()).postDelayed(
                        {
                            TreeDumper.dump(
                                this@DoormanAccessibilityService, label, redact = false,
                            )
                        },
                        delaySeconds * 1000L,
                    )
                } else {
                    TreeDumper.dump(this@DoormanAccessibilityService, label, redact = false)
                }
            }
        }
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(ACTION_DUMP), ContextCompat.RECEIVER_EXPORTED,
        )
        dumpReceiver = receiver
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        stopClock()
        presenceClock.stop()
        flushPresence()
        handler.removeCallbacks(presenceTick)
        scrollBudget.reset()
        serviceScope.cancel()
        handler.removeCallbacks(evaluate)
        handler.removeCallbacks(passExpiry)
        if (::overlay.isInitialized) overlay.hide()
        dumpReceiver?.let { runCatching { unregisterReceiver(it) } }
        dumpReceiver = null
        return super.onUnbind(intent)
    }

    private fun currentImePackage(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let { ComponentName.unflattenFromString(it)?.packageName }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        // Doorman's own windows belong in the same category as the notification
        // shade and the keyboard: they appear over an app without the user
        // having gone anywhere. Counting them as a move was a real bug -- the
        // block overlay raises a window-state event of its own, so a moment
        // after allowing a video someone had sent, the service decided the user
        // had arrived from Doorman, and the allowance vanished under them.
        if (pkg in TRANSIENT_PACKAGES || pkg == imePackage || pkg == packageName) return

        when (event.eventType) {
            // A new screen: decide at once, so the block lands before the feed
            // has a chance to be read.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                logTransition(pkg, event)
                handler.removeCallbacks(evaluate)
                // A short settle: the event arrives while the opening app's
                // window is still being built, and reading it too early sees the
                // launch animation rather than the screen.
                handler.postDelayed(evaluate, WINDOW_SETTLE_MS)
            }
            // Same window, new content -- a tab switch looks like this.
            //
            // A plain debounce starves here. A playing reel emits content
            // changes faster than any sensible quiet window, so every event
            // cancels the pending evaluation and reschedules it, and the check
            // never runs at all -- exactly on the screens that most need
            // blocking. So the coalescing has a ceiling: quiet moments still
            // settle, but the evaluation cannot be postponed indefinitely.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pkg !in rules.supportedPackages) return
                // An app being opened emits content changes before the
                // window-state event that records where the user came from.
                // Judging the screen on those means judging it without knowing
                // how it was reached, which showed up as a block flashing over
                // a video a friend had sent before it was allowed. The
                // window-state event is moments away; wait for it.
                if (pkg != lastPackage) return
                val sinceLast = SystemClock.uptimeMillis() - lastEvaluationAt
                handler.removeCallbacks(evaluate)
                if (sinceLast >= MAX_EVALUATION_INTERVAL_MS) {
                    handler.post(evaluate)
                } else {
                    handler.postDelayed(evaluate, CONTENT_DEBOUNCE_MS)
                }
            }
            // Only meaningful while a shared reel is on screen; everywhere else
            // it is dropped without a tree walk.
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (pkg !in rules.supportedPackages) return
                onScrolled(event)
            }
            else -> Unit
        }
    }

    /**
     * Spends the shared-reel budget. Reaching zero blocks straight away rather
     * than waiting for the next content event, so the second reel never gets a
     * chance to play.
     */
    private fun onScrolled(event: AccessibilityEvent) {
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "scrolled: key=$budgetKey dX=${event.scrollDeltaX} dY=${event.scrollDeltaY} " +
                    "to=${event.toIndex} of ${event.itemCount} src=${event.className}",
            )
        }
        val key = budgetKey ?: return
        val spent = scrollBudget.onScroll(
            key = key,
            deltaX = event.scrollDeltaX,
            deltaY = event.scrollDeltaY,
            // Pagers report the item they moved to; lists often report nothing
            // useful, which is why distance is tracked as well.
            itemIndex = event.toIndex.takeIf { it >= 0 },
            nowMillis = System.currentTimeMillis(),
        )
        if (spent) {
            Log.i(TAG, "shared-reel budget spent; blocking")
            handler.removeCallbacks(evaluate)
            handler.post(evaluate)
        }
    }

    private var lastEvaluationAt = 0L

    /**
     * Lets a reel someone sent you play, and blocks the moment you swipe past
     * it. The viewport is read live so a rotation cannot skew the distance
     * fallback.
     */
    private val scrollBudget = ScrollBudget(
        viewportHeightPx = { resources.displayMetrics.heightPixels },
    )
    private var budgetKey: String? = null

    /**
     * The last screen we ruled on inside a supported app. Arriving at a
     * shared reel *from somewhere else* -- typically back to the conversation
     * and into the next reel -- is a new reel and deserves its own budget.
     * Leaving the app entirely does not update this, so backgrounding and
     * returning cannot be used to keep scrolling.
     */
    private var lastEvaluatedScreenId: String? = null

    /** The app the user was in just before the one in front of them. */
    private var arrivedFromPackage: String? = null

    /**
     * Home-screen apps, which do not count as "somewhere else". Resolved once:
     * the answer only changes if the user installs a new launcher, and asking
     * the package manager on every screen evaluation would be wasteful on
     * exactly the events that arrive most often.
     */
    private val launcherPackages: Set<String> by lazy {
        runCatching {
            packageManager.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
            ).mapNotNull { it.activityInfo?.packageName }.toSet()
        }.getOrDefault(emptySet())
    }

    /**
     * Every package Doorman acts on: those with shipped rules, plus whatever
     * apps the user has chosen to hold themselves.
     */
    private fun watchedPackages(): Set<String> =
        rules.supportedPackages + appLimits.filterValues { !it.isOff }.keys

    private fun evaluateCurrentScreen() {
        evaluateCurrentScreenNow()
        // Whatever was decided -- blocked, allowed, not recognised -- is the
        // state the presence clock should be timing from here on.
        notePresence()
    }

    private fun evaluateCurrentScreenNow() {
        lastEvaluationAt = SystemClock.uptimeMillis()
        val snapshot = SnapshotCapture.capture(this, preferPackage = lastPackage)
        // A game is often one drawing surface with nothing readable in it, so
        // there may be no tree at all. Holding a whole app needs only its name,
        // which the foreground tracking already knows.
        val pkg = snapshot?.packageName ?: lastPackage
        if (pkg == null || pkg !in watchedPackages()) {
            Log.d(TAG, "evaluate: skip pkg=$pkg nodes=${snapshot?.nodes?.size}")
            stopClock()
            if (overlay.isShowing) overlay.hide()
            return
        }
        // A pass suspends blocking for one app until it lapses. Checked here
        // rather than inside the evaluator so the rules stay pure and testable.
        val pass = activePass
        if (pass != null &&
            rules.canonicalPackage(pass.packageName) == rules.canonicalPackage(pkg) &&
            pass.isActiveAt(System.currentTimeMillis())
        ) {
            stopClock()
            if (overlay.isShowing) overlay.hide()
            return
        }

        val arrivedFromAnotherApp = cat.doorman.app.rules.Arrival.isFromAnotherApp(
            previous = arrivedFromPackage,
            current = pkg,
            launcherPackages = launcherPackages,
            self = packageName,
        )
        val verdict = if (snapshot != null && rules.appFor(pkg) != null) {
            ScreenEvaluator.evaluate(snapshot, rules, enabledScreenIds, arrivedFromAnotherApp)
        } else {
            ScreenEvaluator.Verdict.ALLOW
        }
        Log.i(
            TAG,
            "evaluate: pkg=$pkg nodes=${snapshot?.nodes?.size} " +
                "outcome=${verdict.outcome} screen=${verdict.screenId} enabled=$enabledScreenIds",
        )
        currentStatus = "$pkg -> ${verdict.screenId ?: "not recognised"}"


        if (verdict.outcome == ScreenEvaluator.Outcome.ALLOW_ONCE &&
            verdict.screenId != null && snapshot != null
        ) {
            stopClock()
            val key = "${snapshot.packageName}/${verdict.screenId}"
            val arrivedFromElsewhere = lastEvaluatedScreenId != verdict.screenId
            budgetKey = key
            lastEvaluatedScreenId = verdict.screenId
            val now = System.currentTimeMillis()
            if (arrivedFromElsewhere) scrollBudget.reset()
            scrollBudget.begin(key, verdict.budget, now)
            if (scrollBudget.hasBudget(key, now)) {
                if (overlay.isShowing) overlay.hide()
            } else {
                overlay.show(
                    verdict.screenId,
                    labelFor(verdict.labelKey),
                    band = bandFor(snapshot, verdict.screenId),
                    bodyRes = R.string.blocked_body_budget_spent,
                )
            }
            return
        }
        budgetKey = null
        lastEvaluatedScreenId = verdict.screenId

        val canonical = rules.canonicalPackage(pkg) ?: pkg
        val appLimit = appLimits[canonical] ?: Limits.OFF
        val targets = buildList {
            if (!appLimit.isOff) add(Allowances.Target(canonical, appLimit, ledger))
            val screenId = verdict.screenId
            if (screenId != null && verdict.blocked) {
                val limit = screenLimits[screenId] ?: Limits.BLOCKED
                if (!limit.isOff) add(Allowances.Target(screenId, limit, ledger))
            }
        }

        val outcome = Allowances.decide(targets, LocalDateTime.now())
        // The line above logs what the *rules* made of the screen. This logs
        // what actually happens to it, which since allowances arrived is a
        // different question: a rule can match and the screen still be open
        // because there is time left on it.
        Log.i(TAG, "decision: $outcome targets=${targets.map { it.id to it.limits }}")
        when (outcome) {
            is Allowances.Outcome.Allow -> {
                stopClock()
                stoppedTarget = null
                if (overlay.isShowing) overlay.hide()
            }

            is Allowances.Outcome.Block -> {
                stopClock()
                // Counted once per encounter, not once per evaluation. The
                // service re-decides on every content event, so counting here
                // without the guard would score one glance at a blocked feed as
                // several hundred stops and make the report meaningless.
                if (stoppedTarget != outcome.targetId) {
                    stoppedTarget = outcome.targetId
                    serviceScope.launch {
                        Prefs(this@DoormanAccessibilityService).recordToday(stops = 1)
                    }
                }
                showBlock(outcome, snapshot, pkg)
            }

            is Allowances.Outcome.OnTheClock -> {
                stoppedTarget = null
                if (overlay.isShowing) overlay.hide()
                startClock(outcome)
            }
        }
    }

    /**
     * Puts the block up for whatever ran out.
     *
     * A whole app that has been held covers the screen entirely: there is no
     * tab bar worth keeping, and no part of it the user asked to keep. A single
     * screen keeps its band, so the way to the messages stays open.
     */
    private fun showBlock(
        outcome: Allowances.Outcome.Block,
        snapshot: cat.doorman.app.model.ScreenSnapshot?,
        pkg: String,
    ) {
        val body = when (outcome.reason) {
            Allowances.Reason.ALLOWANCE_SPENT -> R.string.blocked_body_allowance_spent
            Allowances.Reason.SCHEDULE -> R.string.blocked_body_schedule
            Allowances.Reason.ALWAYS -> R.string.blocked_body
        }
        val wholeApp = outcome.targetId == rules.canonicalPackage(pkg) ?: pkg ||
            outcome.targetId.contains('.')
        if (wholeApp || snapshot == null) {
            overlay.show(outcome.targetId, appLabelFor(outcome.targetId), bodyRes = body)
            return
        }
        val rule = rules.appFor(pkg)?.screens?.firstOrNull { it.id == outcome.targetId }
        overlay.show(
            outcome.targetId,
            labelFor(rule?.labelKey),
            band = bandFor(snapshot, outcome.targetId),
            bodyRes = body,
        )
    }

    /**
     * A readable name for an app.
     *
     * Apps with shipped rules carry a translated label. For one the user added
     * themselves, the system is asked, and the package name stands in if it
     * declines -- a picker row saying com.some.game is poor, but an empty one
     * is worse.
     */
    private fun appLabelFor(packageName: String): String {
        rules.appFor(packageName)?.labelKey?.let { key ->
            val label = labelFor(key)
            if (label.isNotEmpty() && label != key) return label
        }
        return runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0),
            ).toString()
        }.getOrDefault(packageName)
    }

    /**
     * Starts or continues counting time against a running allowance.
     *
     * The ticker is what actually advances the clock; the evaluation only says
     * what to charge. Its period is short enough that the block lands within a
     * second or so of the allowance running out.
     */
    private fun startClock(outcome: Allowances.Outcome.OnTheClock) {
        allowanceClock.tick(outcome.charge)
        handler.removeCallbacks(clockTick)
        handler.postDelayed(clockTick, CLOCK_TICK_MS)
        clockRunning = true
    }

    private fun stopClock() {
        if (!clockRunning && allowanceClock.pendingMillis() == 0L) return
        handler.removeCallbacks(clockTick)
        clockRunning = false
        allowanceClock.stop()
        flushClock()
    }

    /**
     * One tick of a running allowance.
     *
     * Time spent while the screen is off is not time spent: a phone face down
     * on a table with a game open would otherwise burn through the day's
     * allowance without anyone looking at it.
     */
    private fun onClockTick() {
        val power = getSystemService(android.os.PowerManager::class.java)
        if (power?.isInteractive == false) {
            stopClock()
            return
        }
        evaluateCurrentScreen()
        flushClock()
    }

    /**
     * Writes accumulated time to storage.
     *
     * Not on every tick: the ledger is written at a human pace, because the
     * cost of losing the last few seconds to a crash is a few seconds, and the
     * cost of writing every second forever is the battery.
     */
    private fun flushClock() {
        val now = System.currentTimeMillis()
        val due = now - lastFlushAt >= FLUSH_INTERVAL_MS
        if (!due && clockRunning) return
        val pending = allowanceClock.drain()
        if (pending.isEmpty()) return
        lastFlushAt = now
        val at = LocalDateTime.now()
        // Pending is keyed by "target|PERIOD", because a screen can be on an
        // hourly and a daily budget at once and those are separate counters.
        val charges = pending.mapNotNull { (key, millis) ->
            val period = runCatching { Period.valueOf(key.substringAfterLast('|')) }
                .getOrNull() ?: return@mapNotNull null
            key to Allowances.charge(
                ledger[key] ?: Allowances.Spent.NOTHING,
                period,
                millis,
                at,
            )
        }.toMap()
        if (charges.isEmpty()) return
        ledger = ledger + charges
        // The largest of the charges, not their sum: one stretch of watching is
        // charged to every budget that applies to it, so adding them would
        // report an hour of Reels as two or three.
        val watched = pending.values.maxOrNull() ?: 0L
        serviceScope.launch {
            Prefs(this@DoormanAccessibilityService).let {
                it.chargeSpent(charges)
                if (watched > 0L) it.recordToday(spentMillis = watched)
            }
        }
    }

    private var lastFlushAt = 0L

    // ------------------------------------------------------------ presence

    /**
     * How long each held app, and each recognised screen in it, is in front.
     *
     * Separate from the allowance clock, which only runs where a budget
     * applies. This one runs for any app with anything held in it -- Reels
     * blocked but Stories open still counts the Stories -- because "you spent
     * four hours in Instagram this week" is the sentence somebody who blocked
     * Reels most needs to hear, and no budget would ever have produced it.
     */
    private val presenceClock = PresenceClock { System.currentTimeMillis() }
    private var presenceTicking = false
    private var lastPresenceFlushAt = 0L

    private val presenceTick = Runnable {
        presenceTicking = false
        notePresence()
        flushPresence()
    }

    /**
     * Tells the clock what is in front right now: a held app and, when a rule
     * recognised it, which screen. Nothing when the screen is off, when the
     * app is not held, or when a block is standing -- time spent looking at
     * Doorman's overlay is a stop, and it is already counted as one.
     */
    private fun notePresence() {
        val power = getSystemService(android.os.PowerManager::class.java)
        val pkg = lastPackage
        val canonical = pkg?.let { rules.canonicalPackage(it) ?: it }
        val watching = when {
            power?.isInteractive == false -> emptyList()
            canonical == null || !isHeld(canonical) -> emptyList()
            ::overlay.isInitialized && overlay.isShowing -> emptyList()
            else -> listOfNotNull(
                "$PRESENCE_APP|$canonical",
                lastEvaluatedScreenId?.let { "$PRESENCE_SCREEN|$it" },
            )
        }
        if (watching.isEmpty()) {
            presenceClock.stop()
            flushPresence()
            return
        }
        presenceClock.tick(watching)
        if (!presenceTicking) {
            presenceTicking = true
            handler.postDelayed(presenceTick, PRESENCE_TICK_MS)
        }
    }

    /** An app with at least one screen held, or held as a whole. */
    private fun isHeld(canonical: String): Boolean {
        val app = rules.apps[canonical]
        if (app != null && app.screens.any { it.id in enabledScreenIds }) return true
        return !(appLimits[canonical] ?: Limits.OFF).isOff
    }

    private fun flushPresence() {
        val now = System.currentTimeMillis()
        if (now - lastPresenceFlushAt < FLUSH_INTERVAL_MS && presenceTicking) return
        val pending = presenceClock.drain()
        if (pending.isEmpty()) return
        lastPresenceFlushAt = now
        val apps = pending.filterKeys { it.startsWith("$PRESENCE_APP|") }
            .mapKeys { it.key.substringAfter('|') }
        val screens = pending.filterKeys { it.startsWith("$PRESENCE_SCREEN|") }
            .mapKeys { it.key.substringAfter('|') }
        serviceScope.launch {
            Prefs(this@DoormanAccessibilityService).recordToday(apps = apps, screens = screens)
        }
    }

    /**
     * Which target the block currently standing is for, so one encounter counts
     * once. Cleared the moment the screen is let through again.
     */
    private var stoppedTarget: String? = null

    /**
     * Nothing re-evaluates on its own when a pass runs out, so the block has to
     * be re-armed on a timer; otherwise a lapsed pass leaves the feed open until
     * the next stray accessibility event.
     */
    private fun schedulePassExpiry(pass: Prefs.Pass?) {
        handler.removeCallbacks(passExpiry)
        if (pass == null) return
        val delay = pass.expiresAt - System.currentTimeMillis()
        if (delay > 0) handler.postDelayed(passExpiry, delay + 250)
    }

    /**
     * Rules name a string resource so screen names translate with the app.
     * Resolving by name is what keeps rules.json authoritative: adding a screen
     * should not require touching Kotlin. res/raw/keep.xml stops the shrinker
     * removing labels that no code appears to reference.
     */
    @android.annotation.SuppressLint("DiscouragedApi")
    private fun labelFor(labelKey: String?): String {
        if (labelKey == null) return ""
        val id = resources.getIdentifier(labelKey, "string", packageName)
        return if (id != 0) getString(id) else labelKey
    }

    private fun logTransition(pkg: String, event: AccessibilityEvent) {

        // Transient system windows (notification shade, IME, volume panel) raise
        // window-state events without the user having left the app. Phase 5 needs
        // to not treat those as "went somewhere else", so they are filtered from
        // the transition log now rather than corrupting entry context later.
        if (pkg == lastPackage) return
        val previous = lastPackage
        // Where the user was immediately before this app. On TikTok this is the
        // only thing separating "a friend sent me this" from "I opened the feed".
        if (pkg != lastPackage) arrivedFromPackage = previous
        // Leaving the app ends whatever was on the clock, and the time belongs
        // to the app being left rather than the one being entered.
        stopClock()
        lastPackage = pkg
        notePresence()
        val relevance = if (pkg in rules.supportedPackages) "SUPPORTED" else "ignored"
        Log.i(TAG, "foreground: ${previous ?: "(none)"} -> $pkg [$relevance] window=${event.className}")
    }

    override fun onInterrupt() {
        // Required by the framework. Doorman has nothing to interrupt: it never
        // speaks, vibrates or holds feedback the system might need to cancel.
    }

    /**
     * The slice of screen this block should cover, leaving the search bar above
     * and the tab bar below reachable.
     */
    private fun bandFor(
        snapshot: cat.doorman.app.model.ScreenSnapshot,
        screenId: String?,
    ): OverlayBounds.Band {
        val app = rules.appFor(snapshot.packageName)
        val rule = app?.screens?.firstOrNull { it.id == screenId }
        return OverlayBounds.compute(
            snapshot = snapshot,
            keepVisibleTopViewIds = OverlayBounds.topAnchorsFor(
                rule = rule,
                appLevelAnchors = app?.keepVisibleTopViewIds.orEmpty(),
                enabledScreenIds = enabledScreenIds,
            ),
            keepVisibleViewIds = app?.keepVisibleViewIds.orEmpty(),
            keepVisibleBelowViewIds = app?.keepVisibleBelowViewIds.orEmpty(),
        )
    }

    /** What the service last decided, for the diagnostics screen. */
    fun lastSeen(): String = currentStatus

    /**
     * Captures the screen the user is looking at, after a delay long enough for
     * them to switch to the app that is misbehaving. Always redacted.
     */
    fun captureDiagnostics(delaySeconds: Int, onDone: (java.io.File?) -> Unit) {
        handler.postDelayed(
            { onDone(TreeDumper.dump(this, "report", redact = true)) },
            delaySeconds * 1000L,
        )
    }

    companion object {
        @Volatile
        var instance: DoormanAccessibilityService? = null
            private set

        const val TAG = "Doorman"
        const val ACTION_DUMP = "cat.doorman.app.DUMP"
        private const val PRESENCE_APP = "app"
        private const val PRESENCE_SCREEN = "screen"
        private const val PRESENCE_TICK_MS = 30_000L

        /** Long enough to ride out a burst of feed updates, short enough that
         *  the block still lands before the user has read anything. */
        private const val CONTENT_DEBOUNCE_MS = 250L

        /** Long enough for the incoming window to exist, short enough to beat a thumb. */
        private const val WINDOW_SETTLE_MS = 150L

        /**
         * Hard ceiling on coalescing. A feed that never goes quiet still gets
         * checked this often, which bounds how long a blocked screen can be on
         * display before the overlay lands.
         */
        private const val MAX_EVALUATION_INTERVAL_MS = 500L

        /** How often a running allowance is advanced. */
        private const val CLOCK_TICK_MS = 1_000L

        /** How often accumulated time reaches storage. */
        private const val FLUSH_INTERVAL_MS = 10_000L

        // Phase 5 should replace this with a window-type check
        // (AccessibilityWindowInfo.TYPE_APPLICATION), which catches every
        // non-activity window at once instead of naming them.
        private val TRANSIENT_PACKAGES = setOf(
            "com.android.systemui",
            "android",
        )
    }
}
