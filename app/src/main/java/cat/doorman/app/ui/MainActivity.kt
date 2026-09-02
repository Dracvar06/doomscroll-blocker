package cat.doorman.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.app.LocaleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import cat.doorman.app.R
import cat.doorman.app.data.Prefs
import cat.doorman.app.limits.Allowances
import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.Period
import cat.doorman.app.limits.isLoosening
import cat.doorman.app.rules.RuleLoader
import cat.doorman.app.rules.RuleSet
import androidx.core.content.FileProvider
import cat.doorman.app.service.DoormanAccessibilityService
import java.io.File
import cat.doorman.app.ui.theme.DoormanTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private enum class Screen { HOME, UNLOCK }

/** A settings change that has been asked for but has not taken effect yet. */
private sealed interface PendingChange {
    data class Mode(val id: String, val limits: Limits) : PendingChange
    data class Preset(val ids: Set<String>) : PendingChange

    /**
     * Shortening the wait is itself a way of weakening the app, and the most
     * direct one: set it to Immediate and every other switch becomes instant.
     * So it waits too.
     */
    data class Delay(val seconds: Int) : PendingChange
}

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    private var serviceEnabled by mutableStateOf(false)
    private var rules by mutableStateOf(RuleSet())
    private var screenDefaults: Map<String, Boolean> = emptyMap()
    private var screenLimits by mutableStateOf(emptyMap<String, Limits>())
    private var appLimits by mutableStateOf(emptyMap<String, Limits>())
    private var spent by mutableStateOf(emptyMap<String, Allowances.Spent>())
    private val appIcons by lazy { AppIcons(this) }
    private var openCards by mutableStateOf(emptyMap<String, Boolean>())

    /** Package names of everything with a launcher icon, for the "is it here?" check. */
    private val installedPackages: Set<String> by lazy {
        allApps.map { it.packageName }.toSet()
    }
    private var activePass by mutableStateOf<Prefs.Pass?>(null)
    private var waitSeconds by mutableIntStateOf(Prefs.DEFAULT_WAIT_SECONDS)
    private var passMinutes by mutableIntStateOf(Prefs.DEFAULT_PASS_MINUTES)

    private var screen by mutableStateOf(Screen.HOME)
    private var selectedPackage by mutableStateOf<String?>(null)
    private var secondsRemaining by mutableIntStateOf(0)
    private var wasReset by mutableStateOf(false)
    private var countdown: Job? = null

    private var reportCountdown by mutableIntStateOf(0)
    private var reportFile by mutableStateOf<File?>(null)
    private var reportFailed by mutableStateOf(false)
    private var lastSeen by mutableStateOf<String?>(null)

    private var changeDelaySeconds by mutableIntStateOf(Prefs.DEFAULT_CHANGE_DELAY_SECONDS)
    private var pendingChange by mutableStateOf<PendingChange?>(null)
    private var pendingSeconds by mutableIntStateOf(0)
    private var pendingCountdown: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = Prefs(this)
        rules = RuleLoader.load(this)
        screenDefaults = RuleLoader.screenDefaults(rules)

        lifecycleScope.launch {
            prefs.screenLimits(screenDefaults).collectLatest { screenLimits = it }
        }
        lifecycleScope.launch { prefs.appLimits.collectLatest { appLimits = it } }
        lifecycleScope.launch { prefs.spent.collectLatest { spent = it } }
        lifecycleScope.launch { prefs.openCards.collectLatest { openCards = it } }
        // Deletes the record of watched apps kept by earlier versions.
        lifecycleScope.launch { prefs.forgetWatchedApps() }
        lifecycleScope.launch {
        }
        lifecycleScope.launch { prefs.activePass.collectLatest { activePass = it } }
        lifecycleScope.launch { prefs.waitSeconds.collectLatest { waitSeconds = it } }
        lifecycleScope.launch { prefs.passMinutes.collectLatest { passMinutes = it } }
        lifecycleScope.launch {
            prefs.changeDelaySeconds.collectLatest { changeDelaySeconds = it }
        }

        setContent {
            DoormanTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        PendingChangeDialog(
                            seconds = pendingSeconds,
                            isDelayChange = pendingChange is PendingChange.Delay,
                            targetLabel = (pendingChange as? PendingChange.Mode)?.let { change ->
                                labelKeyFor(change.id).let(::labelFor)
                                    .ifEmpty { appLabelFor(change.id) }
                            },
                            modeLabel = (pendingChange as? PendingChange.Mode)
                                ?.let { change -> summaryText(change.limits) },
                            onCancel = { cancelPendingChange() },
                        )
                        when (screen) {
                            Screen.HOME -> HomeContent()
                            Screen.UNLOCK -> UnlockScreen(
                                rules = rules,
                                selectedPackage = selectedPackage,
                                secondsRemaining = secondsRemaining,
                                passMinutes = passMinutes,
                                wasReset = wasReset,
                                onSelectPackage = { pkg ->
                                    selectedPackage = pkg
                                    wasReset = false
                                    startCountdown()
                                },
                                onGrant = { grantPass() },
                                onBack = { leaveUnlock() },
                                labelFor = ::labelFor,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun HomeContent() {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.bodyLarge)

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(
                        if (serviceEnabled) R.string.status_enabled else R.string.status_disabled
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        if (serviceEnabled) R.string.status_enabled_body
                        else R.string.status_disabled_body
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!serviceEnabled) {
                    // Also the prominent disclosure Google Play requires before
                    // an accessibility service is requested.
                    Text(
                        text = stringResource(R.string.accessibility_service_description),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = {
                        // NEW_TASK matters: without it the settings screen lands
                        // inside Doorman's own task, and tapping the launcher
                        // icon afterwards reopens Android settings.
                        startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) {
                        Text(stringResource(R.string.action_open_accessibility_settings))
                    }
                }
            }
        }

        activePass?.let { pass ->
            val minutesLeft =
                ((pass.expiresAt - System.currentTimeMillis()) / 60_000L).toInt().coerceAtLeast(0)
            if (pass.isActiveAt(System.currentTimeMillis())) {
                Card {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(
                                R.string.pass_active_banner,
                                labelFor(rules.appFor(pass.packageName)?.labelKey ?: ""),
                                minutesLeft + 1,
                            )
                        )
                        TextButton(onClick = { lifecycleScope.launch { prefs.clearPass() } }) {
                            Text(stringResource(R.string.action_end_pass))
                        }
                    }
                }
            }
        }

        BlockingSettings(
            rules = rules,
            screenLimits = screenLimits,
            appLimits = appLimits,
            otherApps = otherApps(),
            icons = appIcons,
            installedPackages = installedPackages,
            openCards = openCards,
            onCardOpenChanged = { pkg, open ->
                lifecycleScope.launch { prefs.setCardOpen(pkg, open) }
            },
            remainingFor = ::remainingLabelFor,
            pendingLabel = (pendingChange as? PendingChange.Mode)
                ?.let { change -> labelKeyFor(change.id).let(::labelFor).ifEmpty { appLabelFor(change.id) } },
            pendingModeLabel = (pendingChange as? PendingChange.Mode)
                ?.let { change -> summaryText(change.limits) },
            pendingIsDelay = pendingChange is PendingChange.Delay,
            pendingSeconds = pendingSeconds,
            changeDelaySeconds = changeDelaySeconds,
            onLimitsChosen = { id, limits -> requestLimitsChange(id, limits) },
            onPreset = { ids -> requestPreset(ids) },
            onCancelPending = { cancelPendingChange() },
            onChangeDelay = { seconds -> requestDelayChange(seconds) },
            labelFor = ::labelFor,
            helpFor = ::helpFor,
        )

        Text(stringResource(R.string.section_pass), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.pass_explainer), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = {
            screen = Screen.UNLOCK
            selectedPackage = null
            wasReset = false
            secondsRemaining = 0
        }) {
            Text(stringResource(R.string.action_get_pass))
        }

        LanguageSection(
            supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            currentTag = appLocaleTag(),
            onPick = { tag -> setAppLocale(tag) },
        )

        DiagnosticsSection(
            lastSeen = lastSeen,
            countdown = reportCountdown,
            reportReady = reportFile != null,
            captureFailed = reportFailed,
            onCapture = { captureReport() },
            onShare = { shareReport() },
        )
    }

    /**
     * Weakening a block waits; strengthening one does not.
     *
     * The delay exists for the moment where the feed is one switch away and the
     * switch is right there. Making it wait, and cancelling if you walk off,
     * costs an impulse the few seconds it needs to pass. Putting the same
     * friction on switching a block *on* would only discourage the decision
     * worth encouraging.
     */
    private fun requestLimitsChange(targetId: String, limits: Limits) {
        val current = screenLimits[targetId] ?: appLimits[targetId] ?: Limits.OFF
        if (!isLoosening(current, limits) || changeDelaySeconds <= 0) {
            lifecycleScope.launch { prefs.setLimits(targetId, limits) }
            return
        }
        startPending(PendingChange.Mode(targetId, limits))
    }

    private fun requestPreset(ids: Set<String>) {
        val weakens = screenLimits.any { (id, limits) -> !limits.isOff && id !in ids }
        if (!weakens || changeDelaySeconds <= 0) {
            lifecycleScope.launch { prefs.setEnabledScreens(ids, screenDefaults.keys) }
            return
        }
        startPending(PendingChange.Preset(ids))
    }

    /**
     * The apps Doorman has watched the user open, minus the ones it already has
     * rules for -- those have their own card, with the screens inside them.
     */
    /**
     * The apps offered for holding as a whole: everything on the phone except
     * the ones with rules of their own, which have their own card.
     */
    private fun otherApps(): List<OtherApp> =
        allApps.filter { rules.appFor(it.packageName) == null }

    /**
     * Every app with a launcher icon, for picking one that has not been opened
     * since Doorman was installed.
     *
     * Resolved once and kept: the list only changes when something is installed
     * or removed, and building it on every recomposition would stutter a
     * scrolling screen.
     */
    private fun appLabelFor(packageName: String): String =
        allApps.firstOrNull { it.packageName == packageName }?.label ?: packageName

    private val allApps: List<OtherApp> by lazy {
        runCatching {
            packageManager.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER),
                0,
            ).mapNotNull { resolved ->
                val info = resolved.activityInfo?.applicationInfo ?: return@mapNotNull null
                if (info.packageName == packageName) return@mapNotNull null
                OtherApp(
                    info.packageName,
                    packageManager.getApplicationLabel(info).toString(),
                )
            }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    /**
     * "3 minutes left", for something with an allowance running.
     *
     * Null for anything held outright or switched off: the mode name already
     * says all there is to say, and a countdown next to "Blocked" would be
     * noise.
     */
    /**
     * Lengthening the wait takes effect at once; shortening it has to sit out
     * the wait that is currently in force. Without this the delay is one tap
     * from being switched off, and everything it protects with it.
     */
    private fun requestDelayChange(seconds: Int) {
        if (seconds >= changeDelaySeconds || changeDelaySeconds <= 0) {
            lifecycleScope.launch { prefs.setChangeDelaySeconds(seconds) }
            return
        }
        startPending(PendingChange.Delay(seconds))
    }

    private fun startPending(change: PendingChange) {
        pendingCountdown?.cancel()
        pendingChange = change
        pendingSeconds = changeDelaySeconds
        pendingCountdown = lifecycleScope.launch {
            while (pendingSeconds > 0) {
                delay(1_000)
                pendingSeconds -= 1
            }
            applyPendingChange()
        }
    }

    private fun applyPendingChange() {
        when (val change = pendingChange) {
            is PendingChange.Mode ->
                lifecycleScope.launch { prefs.setLimits(change.id, change.limits) }
            is PendingChange.Preset ->
                lifecycleScope.launch { prefs.setEnabledScreens(change.ids, screenDefaults.keys) }
            is PendingChange.Delay ->
                lifecycleScope.launch { prefs.setChangeDelaySeconds(change.seconds) }
            null -> Unit
        }
        pendingChange = null
        pendingSeconds = 0
    }

    private fun cancelPendingChange() {
        pendingCountdown?.cancel()
        pendingCountdown = null
        pendingChange = null
        pendingSeconds = 0
    }

    private fun remainingLabelFor(targetId: String): String? {
        val limits = screenLimits[targetId] ?: appLimits[targetId] ?: return null
        val today = java.time.LocalDateTime.now()
        val applicable = limits.allowancesOn(today.dayOfWeek)
        if (applicable.isEmpty()) return null
        val left = applicable.minOf { allowance ->
            cat.doorman.app.limits.Allowances.remainingMillis(
                allowance,
                spent[cat.doorman.app.limits.Allowances.ledgerKey(targetId, allowance.period)]
                    ?: cat.doorman.app.limits.Allowances.Spent.NOTHING,
                today,
            )
        }
        if (left <= 0L) return getString(R.string.remaining_none)
        val minutes = Math.ceil(left / 60_000.0).toInt()
        return getString(
            R.string.remaining_left,
            resources.getQuantityString(R.plurals.minutes, minutes, minutes),
        )
    }

    /**
     * What a set of limits says, outside a composable, for the pending-change
     * dialog. Deliberately terse: the dialog is a countdown, not a manual.
     */
    private fun summaryText(limits: Limits): String = when {
        limits.blocked -> getString(R.string.mode_blocked)
        limits.isOff -> getString(R.string.mode_off)
        else -> buildList {
            limits.allowances.forEach { allowance ->
                val minutes = resources.getQuantityString(
                    R.plurals.minutes, allowance.minutes, allowance.minutes,
                )
                add(
                    getString(
                        when (allowance.period) {
                            Period.HOUR -> R.string.mode_allowance_hour
                            Period.DAY -> R.string.mode_allowance_day
                            Period.WEEK -> R.string.mode_allowance_week
                        },
                        minutes,
                    ),
                )
            }
            limits.windows.forEach { window ->
                add("${formatMinute(window.fromMinute)}\u2013${formatMinute(window.toMinute)}")
            }
        }.joinToString(" \u00b7 ")
    }

    private fun labelKeyFor(screenId: String): String =
        rules.apps.values.flatMap { it.screens }.firstOrNull { it.id == screenId }?.labelKey ?: ""

    /** Null means "whatever the phone is set to". */
    private fun appLocaleTag(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val locales = getSystemService(LocaleManager::class.java)?.applicationLocales
        return locales?.takeIf { !it.isEmpty }?.get(0)?.language
    }

    /**
     * Setting this makes Android recreate the activity in the new language, so
     * there is nothing to refresh by hand.
     */
    private fun setAppLocale(tag: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        getSystemService(LocaleManager::class.java)?.applicationLocales =
            if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }

    /**
     * Gives the user five seconds to switch to the app that is misbehaving, then
     * records the structure of whatever is on screen. Always redacted: these
     * reports are meant to be posted publicly.
     */
    private fun captureReport() {
        val service = DoormanAccessibilityService.instance
        if (service == null) {
            reportFailed = true
            return
        }
        reportFailed = false
        reportFile = null
        reportCountdown = 5
        lifecycleScope.launch {
            while (reportCountdown > 0) {
                delay(1_000)
                reportCountdown -= 1
            }
        }
        service.captureDiagnostics(delaySeconds = 5) { file ->
            reportFile = file
            reportFailed = file == null
        }
    }

    private fun shareReport() {
        val file = reportFile ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.reports", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.action_share_report)))
    }

    private fun startCountdown() {
        countdown?.cancel()
        secondsRemaining = waitSeconds
        countdown = lifecycleScope.launch {
            while (secondsRemaining > 0) {
                delay(1_000)
                secondsRemaining -= 1
            }
        }
    }

    private fun grantPass() {
        val pkg = selectedPackage ?: return
        lifecycleScope.launch {
            prefs.grantPass(pkg, passMinutes * 60_000L, System.currentTimeMillis())
            leaveUnlock()
        }
    }

    private fun leaveUnlock() {
        countdown?.cancel()
        countdown = null
        secondsRemaining = 0
        selectedPackage = null
        wasReset = false
        screen = Screen.HOME
    }

    /**
     * The wait is the whole mechanism, so it must not survive being walked away
     * from. Leaving mid-countdown cancels it and puts the clock back to the
     * start; coming back begins again from the top.
     */
    override fun onStop() {
        super.onStop()
        countdown?.cancel()
        countdown = null
        // Walking away abandons a pending change rather than letting it land
        // while you are elsewhere.
        cancelPendingChange()
        if (screen == Screen.UNLOCK && selectedPackage != null && secondsRemaining > 0) {
            wasReset = true
            secondsRemaining = waitSeconds
        }
    }

    override fun onResume() {
        super.onResume()
        serviceEnabled = isAccessibilityServiceEnabled(this)
        lastSeen = DoormanAccessibilityService.instance?.lastSeen()
        if (screen == Screen.UNLOCK && selectedPackage != null && secondsRemaining > 0) {
            startCountdown()
        }
    }

    /**
     * Rules name a string resource so screen names translate with the app.
     * Resolving by name is what keeps rules.json authoritative; res/raw/keep.xml
     * stops the shrinker removing labels no code appears to reference.
     */
    /**
     * The explanation behind a block's info button, or null if none is written.
     *
     * Distinct from [labelFor], which falls back to echoing the key it could
     * not resolve. That is a useful hint for a missing screen name; as help
     * text it would put "help_ig_reels" in front of the user in a dialog
     * meant to reduce confusion.
     */
    @SuppressLint("DiscouragedApi")
    private fun helpFor(helpKey: String?): String? {
        if (helpKey.isNullOrEmpty()) return null
        val id = resources.getIdentifier(helpKey, "string", packageName)
        return if (id != 0) getString(id) else null
    }

    @SuppressLint("DiscouragedApi")
    private fun labelFor(labelKey: String): String {
        if (labelKey.isEmpty()) return ""
        val id = resources.getIdentifier(labelKey, "string", packageName)
        return if (id != 0) getString(id) else labelKey
    }
}

/**
 * Whether the service is actually running.
 *
 * Parsing Settings.Secure's ENABLED_ACCESSIBILITY_SERVICES string is wrong, and
 * wrong in a way that only appears after reinstalling: updating a package makes
 * the system drop its accessibility services while the persisted string can
 * still name them. AccessibilityManager answers from the registry that actually
 * decides whether events arrive.
 */
fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val expected = ComponentName(context, DoormanAccessibilityService::class.java)
    // Ids come back abbreviated ("pkg/.Class"), so unflatten rather than compare strings.
    return manager
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { ComponentName.unflattenFromString(it.id) == expected }
}
