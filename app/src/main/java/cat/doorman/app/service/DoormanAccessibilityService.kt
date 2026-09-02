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
import cat.doorman.app.rules.RuleLoader
import cat.doorman.app.rules.RuleSet
import cat.doorman.app.rules.ScreenEvaluator
import cat.doorman.app.rules.OverlayBounds
import cat.doorman.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
        serviceScope.launch {
            prefs.enabledScreenIds(screenDefaults).collectLatest {
                enabledScreenIds = it
                Log.i(TAG, "blocking set changed: $it")
                evaluateCurrentScreen()
            }
        }
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
        if (pkg in TRANSIENT_PACKAGES || pkg == imePackage) return

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

    private fun evaluateCurrentScreen() {
        lastEvaluationAt = SystemClock.uptimeMillis()
        val snapshot = SnapshotCapture.capture(this, preferPackage = lastPackage)
        if (snapshot?.packageName == null || snapshot.packageName !in rules.supportedPackages) {
            Log.d(TAG, "evaluate: skip pkg=${snapshot?.packageName} nodes=${snapshot?.nodes?.size}")
            if (overlay.isShowing) overlay.hide()
            return
        }
        // A pass suspends blocking for one app until it lapses. Checked here
        // rather than inside the evaluator so the rules stay pure and testable.
        val pass = activePass
        if (pass != null &&
            rules.canonicalPackage(pass.packageName) ==
            rules.canonicalPackage(snapshot.packageName) &&
            pass.isActiveAt(System.currentTimeMillis())
        ) {
            if (overlay.isShowing) overlay.hide()
            return
        }

        val arrivedFromAnotherApp = cat.doorman.app.rules.Arrival.isFromAnotherApp(
            previous = arrivedFromPackage,
            current = snapshot.packageName,
            launcherPackages = launcherPackages,
            self = packageName,
        )
        val verdict =
            ScreenEvaluator.evaluate(snapshot, rules, enabledScreenIds, arrivedFromAnotherApp)
        Log.i(
            TAG,
            "evaluate: pkg=${snapshot.packageName} nodes=${snapshot.nodes.size} " +
                "outcome=${verdict.outcome} screen=${verdict.screenId} enabled=$enabledScreenIds",
        )
        currentStatus = "${snapshot.packageName} -> ${verdict.screenId ?: "not recognised"}"


        if (verdict.outcome == ScreenEvaluator.Outcome.ALLOW_ONCE && verdict.screenId != null) {
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
        if (verdict.blocked && verdict.screenId != null) {
            overlay.show(
                verdict.screenId,
                labelFor(verdict.labelKey),
                band = bandFor(snapshot, verdict.screenId),
            )
        } else if (overlay.isShowing) {
            overlay.hide()
        }
    }

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
        lastPackage = pkg
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

        // Phase 5 should replace this with a window-type check
        // (AccessibilityWindowInfo.TYPE_APPLICATION), which catches every
        // non-activity window at once instead of naming them.
        private val TRANSIENT_PACKAGES = setOf(
            "com.android.systemui",
            "android",
        )
    }
}
