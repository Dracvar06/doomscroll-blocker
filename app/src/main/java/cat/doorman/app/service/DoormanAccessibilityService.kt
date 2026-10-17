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
import cat.doorman.app.block.MediaPauser
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
    private val mediaPauser: MediaPauser by lazy { MediaPauser(this) { handler.post(retakeSilence) } }
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

    /** A single-item allowance running out of time rather than out of swipes. */
    private val allowanceExpiry = Runnable { evaluateCurrentScreen() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Whatever the post-update check decided a moment ago, the service is
        // here now, so any "Doorman is off" notice it posted is already wrong.
        WeeklyReportNotifier.clearServiceOff(this)
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
     *       --es label shorts [--ei delay 5] [--ez redact true]
     *
     * Text is kept by default -- a developer dumping their own screen. Pass
     * redact when the screen being read belongs to somebody else, or when the
     * dump is only wanted for its view ids, which is the usual case when
     * writing a rule.
     *
     * Gated on BuildConfig.DEBUG so release builds expose no such entry point.
     */
    private fun registerDumpReceiver() {
        if (!BuildConfig.DEBUG) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_WINDOWS) {
                    logWindowTrees()
                    return
                }
                if (intent?.action == ACTION_NOBLOCK) {
                    val on = intent.getBooleanExtra("on", false)
                    overlay.debugSuppress = on
                    Log.i(TAG, "debug: block drawing suppressed=$on")
                    if (!on) evaluateCurrentScreen()
                    return
                }
                val label = intent?.getStringExtra("label")
                val delaySeconds = intent?.getIntExtra("delay", 0) ?: 0
                val redact = intent?.getBooleanExtra("redact", false) ?: false
                // The adb path keeps text: that is a developer dumping their
                // own screen deliberately. The in-app path redacts.
                if (delaySeconds > 0) {
                    Log.i(TAG, "dump scheduled in ${delaySeconds}s")
                    Handler(Looper.getMainLooper()).postDelayed(
                        {
                            TreeDumper.dump(
                                this@DoormanAccessibilityService, label, redact = redact,
                            )
                        },
                        delaySeconds * 1000L,
                    )
                } else {
                    TreeDumper.dump(this@DoormanAccessibilityService, label, redact = redact)
                }
            }
        }
        ContextCompat.registerReceiver(
            this, receiver,
            IntentFilter(ACTION_DUMP).apply {
                addAction(ACTION_NOBLOCK)
                addAction(ACTION_WINDOWS)
            },
            ContextCompat.RECEIVER_EXPORTED,
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
        session = null
        held = null
        budgetBlock = null
        serviceScope.cancel()
        handler.removeCallbacks(evaluate)
        handler.removeCallbacks(passExpiry)
        handler.removeCallbacks(allowanceExpiry)
        handler.removeCallbacks(reconcile)
        handler.removeCallbacks(safetyNet)
        handler.removeCallbacks(pipWatch)
        handler.removeCallbacks(closeStep)
        handler.removeCallbacks(holdCheck)
        handler.removeCallbacks(silence)
        handler.removeCallbacks(retakeSilence)
        retakes.clear()
        holdSince = 0L
        silenceTicking = false
        closingPackage = null
        reconcilePending = false
        pipWatching = false
        pipCharging = false
        blockedWhenLeft = null
        if (::overlay.isInitialized) {
            overlay.hide()
            overlay.hideCover()
        }
        mediaPauser.release()
        dumpReceiver?.let { runCatching { unregisterReceiver(it) } }
        dumpReceiver = null
        return super.onUnbind(intent)
    }

    /**
     * Whether this event came from the window the user is actually in.
     *
     * Unknown windows count as active: the list is a snapshot and a window can
     * be too new to be in it, and missing a real app switch is worse than
     * noticing a stale one -- it is what tells a video someone sent from a feed
     * somebody opened.
     */
    private fun isActiveWindow(event: AccessibilityEvent): Boolean {
        val id = event.windowId
        val window = windows?.firstOrNull { it.id == id } ?: return true
        return window.isActive
    }

    /** Packages that appear over an app without the user having gone anywhere. */
    private fun ignoredPackages(): Set<String> =
        TRANSIENT_PACKAGES + setOfNotNull(imePackage, packageName)

    /** The window list as plain facts, or null when the system will not give one. */
    private fun windowFacts(): List<WindowFact>? {
        val list = windows ?: return null
        val bounds = android.graphics.Rect()
        return list.map { w ->
            w.getBoundsInScreen(bounds)
            WindowFact(
                packageName = w.root?.packageName?.toString(),
                isApplication = w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION,
                active = w.isActive,
                focused = w.isFocused,
                pip = w.isInPictureInPictureMode,
                left = bounds.left, top = bounds.top, right = bounds.right, bottom = bounds.bottom,
            )
        }
    }

    private fun foregroundNow(): Foreground.Result? =
        windowFacts()?.let { Foreground.resolve(it, ignoredPackages()) }

    /**
     * Brings [lastPackage] into line with the window list.
     *
     * Events say what changed; the list says what is true. Whenever they
     * disagree the list wins, which is what un-sticks Doorman after a swipe
     * home that was cancelled: the launcher raised its event, the app came
     * back without raising one of its own, and the app was never looked at
     * again.
     */
    private fun reconcileForeground() {
        val result = foregroundNow()
        handleFloatingWindows(result?.pip.orEmpty())
        val pkg = result?.appPackage ?: return
        if (pkg == lastPackage) return
        logTransition(pkg, "window list")
        handler.removeCallbacks(evaluate)
        handler.postDelayed(evaluate, WINDOW_SETTLE_MS)
    }

    /**
     * An app swiped away turns into a small floating window and carries on.
     * What was true of the app is still true of it: a block that was standing
     * stays standing, and time on the clock keeps running.
     */
    private var blockedWhenLeft: String? = null
    private var pipCharging = false
    private var pipWatching = false
    private val pipWatch = object : Runnable {
        override fun run() {
            pipWatching = false
            reconcileForeground()
        }
    }

    private var holdSince = 0L
    private val holdCheck = Runnable { evaluateCurrentScreen() }

    private fun shouldHoldBlock(): Boolean {
        val blocked = blockedWhenLeft ?: return false
        val now = SystemClock.uptimeMillis()
        if (holdSince == 0L) holdSince = now
        val present = foregroundNow()?.present.orEmpty()
            .map { rules.canonicalPackage(it) ?: it }.toSet()
        val interactive = getSystemService(android.os.PowerManager::class.java)?.isInteractive != false
        return HoldPolicy.keepBlock(blocked, present, interactive, now - holdSince)
    }

    /**
     * What is playing is kept silent for as long as a block stands, and while
     * one is being held. Instagram does not pause when it loses audio focus, it
     * mutes, and the volume-up key unmutes it -- so one press brought the sound
     * of a blocked reel back. When the app takes focus back Doorman hears of it
     * and takes it again at once ([retakeSilence]), so the press is worth a
     * fraction of a second of sound; the tick is the backstop for an app that
     * plays without asking. Nothing can stop an app playing to the speaker if
     * the user is determined; this is as far as Android lets a blocker go.
     *
     * Music in another app is stopped too while the block is on screen, which
     * is the price chosen in [MediaPauser]; it is free again once the user has
     * walked away from the block.
     */
    private var silenceTicking = false
    private val silence = object : Runnable {
        override fun run() {
            silenceTicking = false
            if (!blockStanding()) return
            if (!mediaPauser.holdsFocus) mediaPauser.pauseIfPlaying()
            armSilence()
        }
    }

    private fun blockStanding(): Boolean =
        (::overlay.isInitialized && (overlay.isShowing || overlay.isCoverShowing)) || holdSince != 0L

    private fun armSilence() {
        if (silenceTicking) return
        silenceTicking = true
        handler.postDelayed(silence, SILENCE_TICK_MS)
    }

    /**
     * Focus was taken back while a block stands: take it again now. Capped, so
     * an app built to fight for focus meets the tick instead of a tug of war.
     */
    private val retakes = ArrayDeque<Long>()
    private val retakeSilence: Runnable = Runnable {
        if (!blockStanding()) return@Runnable
        val now = SystemClock.uptimeMillis()
        while (retakes.isNotEmpty() && now - retakes.first() > RETAKE_WINDOW_MS) retakes.removeFirst()
        if (retakes.size >= RETAKE_MAX) return@Runnable
        if (mediaPauser.pauseIfPlaying(quick = true)) {
            retakes.addLast(now)
            Log.i(TAG, "media pause: focus taken back, silenced again")
        }
        armSilence()
    }

    /** A block has just landed: stop what was playing, and keep it stopped. */
    private fun blockLanded() {
        mediaPauser.pauseIfPlaying()
        armSilence()
    }

    /**
     * Android gives no way to close another app's floating window, and the
     * system's own close button is not in the tree for Doorman to press. What
     * does work, without any new permission: bring the app back to full screen,
     * which ends the floating window, and step back out of the video. The app
     * is blocked when it appears, as it always is.
     *
     * If the app does not come to the front -- some versions of Android refuse
     * a launch from the background -- that app's cover falls back to leaving the
     * system's close button reachable, rather than leaving a button that does
     * nothing.
     */
    private val closeFailed = mutableSetOf<String>()
    private var closingPackage: String? = null
    private var closingCanonical: String? = null
    private var closingSince = 0L
    private val closeStep = object : Runnable {
        override fun run() {
            val pkg = closingPackage ?: return
            if (foregroundNow()?.appPackage == pkg) {
                closingPackage = null
                Log.i(TAG, "close video: $pkg is back, stepping out")
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }
            if (SystemClock.uptimeMillis() - closingSince > CLOSE_TIMEOUT_MS) {
                closingPackage = null
                closingCanonical?.let { closeFailed += it }
                Log.w(TAG, "close video: $pkg did not come to the front; leaving the system button reachable")
                reconcileForeground()
                return
            }
            handler.postDelayed(this, CLOSE_STEP_MS)
        }
    }

    private fun closeFloatingVideo(pkg: String, canonical: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = launch != null && runCatching { startActivity(launch) }.isSuccess
        if (!started) {
            closeFailed += canonical
            Log.w(TAG, "close video: could not launch $pkg")
            reconcileForeground()
            return
        }
        closingPackage = pkg
        closingCanonical = canonical
        closingSince = SystemClock.uptimeMillis()
        handler.removeCallbacks(closeStep)
        handler.postDelayed(closeStep, CLOSE_STEP_MS)
    }

    private fun handleFloatingWindows(pip: List<WindowFact>) {
        val floating = pip.firstOrNull { it.packageName in watchedPackages() }
        if (floating == null) {
            if (overlay.isCoverShowing) overlay.hideCover()
            if (pipCharging) {
                pipCharging = false
                stopClock()
            }
            return
        }
        val canonical = rules.canonicalPackage(floating.packageName) ?: floating.packageName!!
        val appLimit = appLimits[canonical] ?: Limits.OFF
        val appOutcome = if (appLimit.isOff) {
            null
        } else {
            Allowances.decide(listOf(Allowances.Target(canonical, appLimit, ledger)), LocalDateTime.now())
        }
        when (val action = PipPolicy.decide(blockedWhenLeft == canonical, appOutcome)) {
            PipPolicy.Action.Cover -> {
                if (pipCharging) {
                    pipCharging = false
                    stopClock()
                }
                val canClose = canonical !in closeFailed
                overlay.showCover(
                    android.graphics.Rect(floating.left, floating.top, floating.right, floating.bottom),
                    getString(R.string.pip_cover_label),
                    getString(R.string.pip_cover_hint),
                    closeLabel = if (canClose) getString(R.string.pip_close_video) else null,
                    onClose = if (canClose) {
                        { closeFloatingVideo(floating.packageName ?: canonical, canonical) }
                    } else {
                        null
                    },
                )
                // Kept at it: the window can be tapped to play again blind.
                if (mediaPauser.pauseIfPlaying()) Log.i(TAG, "floating window of $canonical paused")
            }

            is PipPolicy.Action.Charge -> {
                if (overlay.isCoverShowing) overlay.hideCover()
                val interactive = getSystemService(android.os.PowerManager::class.java)?.isInteractive != false
                if (interactive) {
                    allowanceClock.tick(action.ledgerKeys)
                    clockRunning = true
                    pipCharging = true
                    flushClock()
                } else if (pipCharging) {
                    pipCharging = false
                    stopClock()
                }
            }

            PipPolicy.Action.None -> if (overlay.isCoverShowing) overlay.hideCover()
        }
        if (!pipWatching) {
            pipWatching = true
            handler.postDelayed(pipWatch, PIP_WATCH_MS)
        }
    }

    /** Window-list events come in bursts, one per frame of an animation. */
    private fun scheduleReconcile() {
        if (reconcilePending) return
        reconcilePending = true
        handler.postDelayed(reconcile, RECONCILE_DELAY_MS)
    }

    private var reconcilePending = false
    private val reconcile = Runnable {
        reconcilePending = false
        reconcileForeground()
    }

    /**
     * For a while after the foreground changes, look again every couple of
     * seconds. No event is guaranteed to arrive when an app comes back, and a
     * block that stays hidden over an app that is in front is the failure that
     * matters; this bounds how long one can last.
     */
    private var safetyNetUntil = 0L
    private val safetyNet = object : Runnable {
        override fun run() {
            reconcileForeground()
            if (SystemClock.uptimeMillis() < safetyNetUntil) {
                handler.postDelayed(this, SAFETY_NET_MS)
            }
        }
    }

    private fun armSafetyNet() {
        safetyNetUntil = SystemClock.uptimeMillis() + SAFETY_NET_WINDOW_MS
        handler.removeCallbacks(safetyNet)
        handler.postDelayed(safetyNet, SAFETY_NET_MS)
    }

    /** The app left for the launcher, and how it had been reached, for the swipe-and-return case. */
    private var leftApp: String? = null
    private var arrivalBeforeLeaving: String? = null
    private var leftAt = 0L

    /**
     * Debug builds only: one line per window, so a gesture or a floating window
     * can be read off the log instead of guessed at.
     */
    private fun logWindows(reason: String) {
        if (!BuildConfig.DEBUG) return
        val list = windows ?: return
        val bounds = android.graphics.Rect()
        val lines = list.joinToString(" | ") { w ->
            w.getBoundsInScreen(bounds)
            val pkg = w.root?.packageName
            "id=${w.id} type=${w.type} pkg=$pkg active=${w.isActive} focused=${w.isFocused} " +
                "pip=${w.isInPictureInPictureMode} " +
                "layer=${w.layer} b=${bounds.flattenToString()}"
        }
        Log.i(TAG, "windows[$reason]: $lines")
    }

    /**
     * Debug builds only: every window's tree, structure but no text, to see
     * what the system exposes of windows that are not apps (the floating
     * window's own controls, for one).
     */
    private fun logWindowTrees() {
        if (!BuildConfig.DEBUG) return
        val list = windows ?: return
        for (w in list) {
            val root = w.root
            val b = android.graphics.Rect().also { w.getBoundsInScreen(it) }
            Log.i(TAG, "WIN id=${w.id} type=${w.type} pkg=${root?.packageName} layer=${w.layer} " +
                "pip=${w.isInPictureInPictureMode} b=${b.flattenToString()} childCount=${root?.childCount}")
            fun walk(n: android.view.accessibility.AccessibilityNodeInfo?, depth: Int, budget: IntArray) {
                if (n == null || budget[0] <= 0 || depth > 12) return
                budget[0]--
                val nb = android.graphics.Rect().also { n.getBoundsInScreen(it) }
                Log.i(TAG, "   ".repeat(depth) + "N ${n.className} id=${n.viewIdResourceName} " +
                    "click=${n.isClickable} vis=${n.isVisibleToUser} desc=${n.contentDescription != null} " +
                    "b=${nb.flattenToString()}")
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, budget)
            }
            if (root != null && root.packageName?.toString() != packageName) walk(root, 1, intArrayOf(40))
        }
    }

    private fun currentImePackage(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let { ComponentName.unflattenFromString(it)?.packageName }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Window-list changes usually carry no package, so they are looked at
        // before the package check below.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            logWindows("windows-changed type=${event.windowChanges}")
            scheduleReconcile()
            return
        }
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
                // An app being left raises one of these *after* the app being
                // opened does, and taking it at face value made the app the
                // user had just left the "current" one. Everything after that
                // was judged against the wrong package: content changes from
                // the app actually in front were dropped as coming from
                // somewhere else, so the screen was never looked at again and
                // whatever block was up stayed up over the wrong screen.
                logWindows("state-changed pkg=$pkg")
                // The window list decides. A stale event -- the launcher
                // reporting itself after an app has already come back -- names
                // a package that is not the active one and is dropped; if the
                // list is wrong the other way, its own change event follows
                // and reconciles. Only when the list cannot say is the event
                // judged by its own window, as before.
                val foreground = foregroundNow()?.appPackage
                if (foreground != null) {
                    if (foreground != pkg) {
                        scheduleReconcile()
                        return
                    }
                } else if (!isActiveWindow(event)) {
                    return
                }
                logTransition(pkg, event.className)
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
                if (pkg != lastPackage) {
                    // Possibly an app that came back without announcing itself.
                    // Cheap to ask; the list will say if it is really in front.
                    scheduleReconcile()
                    return
                }
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
            // A page turn is a page of the list, not a screenful: the pager
            // rarely fills the display.
            scrolledViewHeightPx = scrolledViewHeight(event),
        )
        if (spent) {
            Log.i(TAG, "shared-reel budget spent; blocking")
            // From what the last look already knew, not a fresh one. Reading the
            // tree while the app animates the swipe waits on the app's own
            // thread -- over a second, measured -- and that second was the next
            // reel playing. The evaluation that follows confirms it.
            budgetBlock?.takeIf { it.key == key }?.let { b ->
                if (overlay.show(b.screenId, b.label, band = b.band, bodyRes = R.string.blocked_body_budget_spent)) {
                    blockLanded()
                }
            }
            handler.removeCallbacks(evaluate)
            handler.post(evaluate)
        }
    }

    private var lastEvaluationAt = 0L

    /** The block a spent single item gets, ready before the swipe that spends it. */
    private class BudgetBlock(val key: String, val screenId: String, val label: String, val band: OverlayBounds.Band)
    private var budgetBlock: BudgetBlock? = null

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
    /**
     * How tall the view that just scrolled is, when the event names it. Costs a
     * binder call, which is why it is only asked on the scroll events of a
     * screen that has a budget running -- a handful per reel, not per frame.
     */
    private fun scrolledViewHeight(event: AccessibilityEvent): Int? {
        val source = event.source ?: return null
        val bounds = android.graphics.Rect()
        source.getBoundsInScreen(bounds)
        return bounds.height().takeIf { it > 0 }
    }

    private var session: ScrollSession.Session? = null

    /** The blocked screen last seen, which a comments sheet may now be covering. */
    private var held: CoverMemory.Held? = null

    /** What the rules call the current screen, held or not. Null when nothing matches. */
    private var lastRecognisedScreenId: String? = null

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
            if (overlay.isShowing) {
                // A swipe home that is held halfway makes the launcher the active
                // window while the app is still there, shrunk to a card with its
                // video playing. The block stays for a moment; a real Home takes
                // the app's window away and the block goes with it.
                if (shouldHoldBlock()) {
                    handler.removeCallbacks(holdCheck)
                    handler.postDelayed(holdCheck, HOLD_CHECK_MS)
                    armSilence()
                    return
                }
                overlay.hide()
            }
            holdSince = 0L
            return
        }
        // Back in a watched app: whatever was being held is over.
        holdSince = 0L
        // A pass suspends blocking for one app until it lapses. Checked here
        // rather than inside the evaluator so the rules stay pure and testable.
        val pass = activePass
        if (pass != null &&
            rules.canonicalPackage(pass.packageName) == rules.canonicalPackage(pkg) &&
            pass.isActiveAt(System.currentTimeMillis())
        ) {
            stopClock()
            // Nothing is being counted under a pass, so no swipe can spend it.
            budgetKey = null
            budgetBlock = null
            if (overlay.isShowing) overlay.hide()
            return
        }

        val arrivedFromAnotherApp = cat.doorman.app.rules.Arrival.isFromAnotherApp(
            previous = arrivedFromPackage,
            current = pkg,
            launcherPackages = launcherPackages,
            self = packageName,
        )
        val ruleVerdict = if (snapshot != null && rules.appFor(pkg) != null) {
            ScreenEvaluator.evaluate(snapshot, rules, enabledScreenIds, arrivedFromAnotherApp)
        } else {
            ScreenEvaluator.Verdict.ALLOW
        }
        // For the presence clock, not for blocking: the screen's name whether
        // or not it is held, so time in an unblocked Stories is called Stories.
        lastRecognisedScreenId = snapshot?.let {
            ScreenEvaluator.recognise(it, rules, arrivedFromAnotherApp)
        }
        // A comments sheet hides the screen under it from the tree. If a blocked
        // screen was the last one seen and a known sheet is over it, the sheet
        // is that screen: same block, same limits, same clock.
        val cover = CoverMemory.step(
            held = held,
            packageName = pkg,
            verdict = ruleVerdict,
            recognisedScreenId = lastRecognisedScreenId,
            snapshot = snapshot,
            coverViewIds = rules.appFor(pkg)?.coverViewIds.orEmpty(),
        )
        held = cover.held
        val verdict = cover.verdict
        if (verdict !== ruleVerdict) lastRecognisedScreenId = verdict.screenId
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
            val arrivedFromElsewhere = ScrollSession.startsFresh(
                session, snapshot.packageName ?: pkg, verdict.screenId,
            )
            budgetKey = key
            val band = bandFor(snapshot, verdict.screenId)
            budgetBlock = BudgetBlock(key, verdict.screenId, labelFor(verdict.labelKey), band)
            session = ScrollSession.Session(snapshot.packageName ?: pkg, verdict.screenId, key)
            val now = System.currentTimeMillis()
            if (arrivedFromElsewhere) scrollBudget.reset()
            scrollBudget.begin(key, verdict.budget, now, verdict.allowMillis)
            handler.removeCallbacks(allowanceExpiry)
            if (scrollBudget.hasBudget(key, now)) {
                if (blockedWhenLeft == (rules.canonicalPackage(pkg) ?: pkg)) blockedWhenLeft = null
                if (overlay.isShowing) overlay.hide()
                // A Short plays in silence -- no scroll, no content change --
                // so nothing would bring us back here to notice the time was
                // up. Come back on purpose.
                scrollBudget.remainingMillis(key, now)?.let { left ->
                    handler.postDelayed(allowanceExpiry, left + 250)
                }
            } else {
                val shown = overlay.show(
                    verdict.screenId,
                    labelFor(verdict.labelKey),
                    band = band,
                    bodyRes = R.string.blocked_body_budget_spent,
                )
                if (shown) blockLanded()
            }
            return
        }
        // Scrolls are only counted on the item itself, so the comments list is
        // not a swipe past it. But the session outlives the item being covered:
        // whatever the rules cannot name -- comments, a share sheet, the
        // author's page -- is not the item being left, and coming back must not
        // look like a new one.
        budgetKey = null
        budgetBlock = null
        handler.removeCallbacks(allowanceExpiry)
        val ending = session?.let { open ->
            rules.appFor(pkg)?.screens?.firstOrNull { it.id == open.screenId }?.sessionEndViewIds
        }.orEmpty()
        if (!ScrollSession.continues(session, snapshot?.packageName ?: pkg, lastRecognisedScreenId, snapshot, ending)) {
            session = null
        }

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
                if (blockedWhenLeft == canonical) blockedWhenLeft = null
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
                if (blockedWhenLeft == canonical) blockedWhenLeft = null
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
            if (overlay.show(outcome.targetId, appLabelFor(outcome.targetId), bodyRes = body)) {
                blockLanded()
            }
            return
        }
        val rule = rules.appFor(pkg)?.screens?.firstOrNull { it.id == outcome.targetId }
        val shown = overlay.show(
            outcome.targetId,
            labelFor(rule?.labelKey),
            band = bandFor(snapshot, outcome.targetId),
            bodyRes = body,
        )
        if (shown) blockLanded()
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
                lastRecognisedScreenId?.let { "$PRESENCE_SCREEN|$it" },
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

    private fun logTransition(pkg: String, detail: CharSequence?) {

        // Transient system windows (notification shade, IME, volume panel) raise
        // window-state events without the user having left the app. Phase 5 needs
        // to not treat those as "went somewhere else", so they are filtered from
        // the transition log now rather than corrupting entry context later.
        if (pkg == lastPackage) return
        val previous = lastPackage
        // Leaving a held app with a block standing over it is remembered, so
        // that if the app carries on in a floating window it is still blocked.
        if (previous != null && previous in watchedPackages() &&
            ::overlay.isInitialized && overlay.isShowing
        ) {
            blockedWhenLeft = rules.canonicalPackage(previous) ?: previous
        }
        // Where the user was immediately before this app. On TikTok this is the
        // only thing separating "a friend sent me this" from "I opened the feed".
        val now = SystemClock.uptimeMillis()
        val arrivalBefore = arrivedFromPackage
        arrivedFromPackage = cat.doorman.app.rules.Arrival.arrivalAfterReturn(
            previous = previous,
            current = pkg,
            leftApp = leftApp,
            arrivalBeforeLeaving = arrivalBeforeLeaving,
            sinceLeftMillis = now - leftAt,
            launcherPackages = launcherPackages,
        )
        if (pkg in launcherPackages && previous != null && previous !in launcherPackages) {
            leftApp = previous
            arrivalBeforeLeaving = arrivalBefore
            leftAt = now
        }
        // Leaving the app ends whatever was on the clock, and the time belongs
        // to the app being left rather than the one being entered.
        stopClock()
        lastPackage = pkg
        lastRecognisedScreenId = null
        notePresence()
        val relevance = if (pkg in rules.supportedPackages) "SUPPORTED" else "ignored"
        Log.i(TAG, "foreground: ${previous ?: "(none)"} -> $pkg [$relevance] via=$detail")
        armSafetyNet()
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
            keepVisibleBottomPx = ((app?.keepVisibleBottomDp ?: 0) *
                resources.displayMetrics.density).toInt(),
            screenHeightPx = resources.displayMetrics.heightPixels,
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
        const val ACTION_NOBLOCK = "cat.doorman.app.NOBLOCK"
        const val ACTION_WINDOWS = "cat.doorman.app.WINDOWS"
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

        /** Window-list events are coalesced for this long before the list is read. */
        private const val RECONCILE_DELAY_MS = 60L

        /** How often, and for how long, the foreground is re-read after it changes. */
        private const val SAFETY_NET_MS = 2_000L
        private const val SAFETY_NET_WINDOW_MS = 30_000L

        /** How often a held block is looked at again, and how often sound is checked. */
        private const val HOLD_CHECK_MS = 200L
        private const val SILENCE_TICK_MS = 1_000L

        /** At most this many immediate retakes of audio focus in this long. */
        private const val RETAKE_MAX = 5
        private const val RETAKE_WINDOW_MS = 10_000L

        /** Closing a floating video: how often to look, and how long to wait for the app. */
        private const val CLOSE_STEP_MS = 250L
        private const val CLOSE_TIMEOUT_MS = 3_000L

        /** How often a floating window is looked at again while it exists. */
        private const val PIP_WATCH_MS = 1_000L

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
