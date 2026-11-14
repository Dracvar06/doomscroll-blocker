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
import androidx.compose.animation.Crossfade
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import cat.doorman.app.R
import cat.doorman.app.report.UsageAccess
import cat.doorman.app.report.WatchedUse
import cat.doorman.app.report.WeeklyReportNotifier
import cat.doorman.app.data.Prefs
import cat.doorman.app.data.SetupStage
import cat.doorman.app.gray.GrayPolicy
import cat.doorman.app.gray.GraySetting
import cat.doorman.app.gray.GrayscaleController
import cat.doorman.app.limits.Allowances
import cat.doorman.app.limits.HardTimes
import cat.doorman.app.limits.Limits
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

/**
 * The three things Doorman is for, one tab each.
 *
 * The settings screen had grown into a single scroll holding what Doorman
 * blocks, how it behaves, the language picker and a diagnostics tool, and
 * folding the last three away only made a shorter scroll -- the report, which
 * has nothing to do with any of them, had nowhere to live at all.
 *
 * A bar also answers the question the report raised: a weekly notification
 * cannot be the only way to see it. An app that punishes you for missing a
 * notification is an app teaching you to watch your notifications, which is the
 * opposite of what this one is for. The notification is a nudge; the tab is the
 * door.
 */
private enum class Tab { BLOCKS, REPORT, SETTINGS }

/** Under a minute is not worth a line of its own in a report. */
private const val REPORTABLE_MILLIS = 60_000L

private fun iconFor(tab: Tab) = when (tab) {
    Tab.BLOCKS -> Icons.Default.Lock
    Tab.REPORT -> Icons.Default.DateRange
    Tab.SETTINGS -> Icons.Default.Settings
}

private fun tabLabel(tab: Tab) = when (tab) {
    Tab.BLOCKS -> R.string.tab_blocks
    Tab.REPORT -> R.string.tab_report
    Tab.SETTINGS -> R.string.tab_settings
}

/** A settings change that has been asked for but has not taken effect yet. */
private sealed interface PendingChange {
    /** [from] is kept for the dialog to say what is being left behind. */
    data class Mode(val id: String, val from: Limits, val limits: Limits) : PendingChange

    /** [name] is the preset's own, for the dialog and for the note once it lands. */
    data class Preset(val name: String, val ids: Set<String>) : PendingChange

    /** Grey switched off or cut back for a screen or app; null is "in colour". */
    data class Gray(val id: String, val from: GraySetting?, val setting: GraySetting?) :
        PendingChange

    /**
     * One save that loosens the limits and the grey together. One countdown and
     * one dialog rather than two in a row: both land when it ends and both are
     * dropped if it is cancelled.
     */
    data class Both(
        val id: String,
        val fromLimits: Limits,
        val limits: Limits,
        val fromGray: GraySetting?,
        val setting: GraySetting?,
    ) : PendingChange

    /**
     * Shortening the wait is itself a way of weakening the app, and the most
     * direct one: set it to Immediate and every other switch becomes instant.
     * So it waits too.
     */
    data class Delay(val seconds: Int) : PendingChange
}

class MainActivity : ComponentActivity() {

    /**
     * Asked for the moment the report is switched on, and never otherwise.
     *
     * The answer is not stored and nothing is undone if it is no: the report
     * still records, and its tab is still there to open by hand. A refusal
     * means "do not interrupt me", not "do not keep this".
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationsGranted = it || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        }

    private lateinit var prefs: Prefs

    private var serviceEnabled by mutableStateOf(false)
    private var rules by mutableStateOf(RuleSet())
    private var screenDefaults: Map<String, Boolean> = emptyMap()
    private var screenLimits by mutableStateOf(emptyMap<String, Limits>())
    private var appLimits by mutableStateOf(emptyMap<String, Limits>())
    private var spent by mutableStateOf(emptyMap<String, Allowances.Spent>())
    private var hardTimes by mutableStateOf(HardTimes.EMPTY)
    private val appIcons by lazy { AppIcons(this) }

    /** Package names of everything with a launcher icon, for the "is it here?" check. */
    private val installedPackages: Set<String> by lazy {
        allApps.map { it.packageName }.toSet()
    }
    private var activePass by mutableStateOf<Prefs.Pass?>(null)
    private var waitSeconds by mutableIntStateOf(Prefs.DEFAULT_WAIT_SECONDS)
    private var passMinutes by mutableIntStateOf(Prefs.DEFAULT_PASS_MINUTES)

    private var screen by mutableStateOf(Screen.HOME)
    private var tab by mutableStateOf(Tab.BLOCKS)

    /**
     * The app whose page is open on the Blocks tab, or null for the list. A
     * page inside a tab rather than a screen of its own: the bottom bar stays,
     * and the list underneath keeps its place.
     */
    private var openApp by mutableStateOf<String?>(null)

    /** The diagnostics page inside Settings, likewise. */
    private var showDiagnostics by mutableStateOf(false)

    /** The Grayscale page on the Blocks tab, likewise. */
    private var showGrayscale by mutableStateOf(false)
    private var journal by mutableStateOf(emptyList<cat.doorman.app.limits.DayRecord>())
    private var weeklyReport by mutableStateOf(true)
    private var redirect by mutableStateOf<cat.doorman.app.data.Redirect?>(null)
    private var graySettings by mutableStateOf(emptyMap<String, GraySetting>())

    /** Whether Doorman has been given the permission grayscale needs. */
    private var grayCanWrite by mutableStateOf(false)

    private var watchedUse by mutableStateOf<WatchedUse?>(null)

    /**
     * Null until read. Not false: defaulting to "not seen" would flash the
     * walkthrough at every returning user for the length of one disk read.
     */
    private var tutorialSeen by mutableStateOf<Boolean?>(null)

    /** Closed until read, so an install past setup never sees the card flash. */
    private var setupStage by mutableStateOf(SetupStage.CLOSED)

    /** Always true before Android 13, where notifications need no permission. */
    private var notificationsGranted by mutableStateOf(true)
    private var coffeeNudgedOn by mutableStateOf<String?>(null)

    /** So opening the report twice in one sitting is not two prompts. */
    private var hasAskedToNotify = false
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
    private var pendingTotalSeconds by mutableIntStateOf(0)
    private var pendingCountdown: Job? = null
    private val snackbar = SnackbarHostState()

    /** Set when leaving dropped a pending change, so coming back can say so. */
    private var droppedWhileAway = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = Prefs(this)
        // Read before the first frame: starting at false drew the red "off"
        // card on every launch until onResume corrected it.
        serviceEnabled = isAccessibilityServiceEnabled(this)
        rules = RuleLoader.load(this)
        screenDefaults = RuleLoader.screenDefaults(rules)
        openReportIfAsked(intent)

        lifecycleScope.launch {
            prefs.screenLimits(screenDefaults).collectLatest { screenLimits = it }
        }
        lifecycleScope.launch { prefs.appLimits.collectLatest { appLimits = it } }
        lifecycleScope.launch { prefs.spent.collectLatest { spent = it } }
        lifecycleScope.launch { prefs.hardTimes.collectLatest { hardTimes = it } }
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
        lifecycleScope.launch { prefs.journal.collectLatest { journal = it } }
        lifecycleScope.launch { prefs.tutorialSeen.collectLatest { tutorialSeen = it } }
        lifecycleScope.launch { prefs.setupStage.collectLatest { setupStage = it } }
        lifecycleScope.launch { prefs.coffeeNudgedOn.collectLatest { coffeeNudgedOn = it } }
        lifecycleScope.launch { prefs.redirect.collectLatest { redirect = it } }
        lifecycleScope.launch { prefs.grayscale.collectLatest { graySettings = it } }
        lifecycleScope.launch {
            prefs.weeklyReport.collectLatest { on ->
                weeklyReport = on
                if (on) {
                    WeeklyReportNotifier.schedule(this@MainActivity)
                } else {
                    WeeklyReportNotifier.cancel(this@MainActivity)
                }
                // Somebody standing on the report when they switch it off
                // has to be put somewhere; leaving them on a tab that no
                // longer has a button would strand them.
                if (!on && tab == Tab.REPORT) tab = Tab.SETTINGS
            }
        }

        setContent {
            CompositionLocalProvider(LocalHardTimes provides hardTimes) {
                DoormanTheme {
                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        snackbarHost = { SnackbarHost(snackbar) },
                        bottomBar = {
                            // Hidden while getting a pass. That flow is a decision
                            // with a countdown running, and offering a way to wander
                            // off mid-way would be offering a way to lose it.
                            // Hidden during the walkthrough too: four pages with
                            // a Next button are not a place to be offered three
                            // other places to be.
                            if (screen == Screen.HOME && tutorialSeen == true) {
                                NavigationBar {
                                    Tab.entries.filter { it != Tab.REPORT || weeklyReport }
                                        .forEach { entry ->
                                        NavigationBarItem(
                                            selected = tab == entry,
                                            onClick = {
                                                // Tapping the tab already open goes
                                                // back to its top, as on every
                                                // other app with a bar.
                                                if (tab == entry) {
                                                    openApp = null
                                                    showDiagnostics = false
                                                    showGrayscale = false
                                                }
                                                tab = entry
                                                // Asked here rather than at first
                                                // launch. The report is on by
                                                // default, so the switch is often
                                                // never touched, and a permission
                                                // dialog on the very first screen
                                                // is a dialog about nothing. Here
                                                // the user is looking at the thing
                                                // being offered.
                                                if (entry == Tab.REPORT) {
                                                    askToNotify()
                                                    refreshUsage()
                                                }
                                            },
                                            icon = { Icon(iconFor(entry), contentDescription = null) },
                                            label = { Text(stringResource(tabLabel(entry))) },
                                        )
                                    }
                                }
                            }
                        },
                    ) { insets ->
                        pendingChange?.let { change ->
                            PendingChangeDialog(
                                seconds = pendingSeconds,
                                totalSeconds = pendingTotalSeconds,
                                title = pendingTitle(change, pendingSeconds),
                                details = pendingDetails(change),
                                onCancel = { cancelPendingChange() },
                            )
                        }
                        // System Back on the pass screen goes back to Doorman, the
                        // way the Back button on it does. Without this the gesture
                        // closed the whole app.
                        BackHandler(enabled = screen == Screen.UNLOCK) { leaveUnlock() }
                        // Back from a page inside a tab goes to that tab's list.
                        BackHandler(
                            enabled = screen == Screen.HOME &&
                                ((tab == Tab.BLOCKS && (openApp != null || showGrayscale)) ||
                                    (tab == Tab.SETTINGS && showDiagnostics)),
                        ) {
                            openApp = null
                            showDiagnostics = false
                            showGrayscale = false
                        }
                        Box(modifier = Modifier.fillMaxSize().padding(insets)) {
                            when (screen) {
                                Screen.HOME -> if (tutorialSeen == false) {
                                    run {
                                        Tutorial(
                                            languageSupported =
                                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                                            languageTag = appLocaleTag(),
                                            onPickLanguage = { tag -> setAppLocale(tag) },
                                            onCoffee = { openCoffee() },
                                            onDone = {
                                                lifecycleScope.launch {
                                                    // Stamped now so the first seasonal
                                                    // reminder is a season after install,
                                                    // not the first time the report opens.
                                                    prefs.setCoffeeNudgedOn(java.time.LocalDate.now().toString())
                                                    prefs.finishTutorial()
                                                }
                                            },
                                        )
                                    }
                                } else if (tutorialSeen == true) Crossfade(
                                    targetState = tab,
                                    label = "tab",
                                ) { shown ->
                                    // One scroll position per tab, and a fresh one
                                    // each visit. A single scroll shared by all
                                    // three tabs opened the report at whatever
                                    // depth the blocks list had been left at --
                                    // usually its last card, with the headline
                                    // somewhere off the top.
                                    key(shown) {
                                        // Made here, above the choice between the
                                        // list and a page, so opening an app and
                                        // coming back finds the list where it was
                                        // left.
                                        val listScroll = rememberScrollState()
                                        val onPage = (shown == Tab.BLOCKS && (openApp != null || showGrayscale)) ||
                                            (shown == Tab.SETTINGS && showDiagnostics)
                                        val body: @Composable ColumnScope.() -> Unit = {
                                            when (shown) {
                                                Tab.BLOCKS -> BlocksTab()
                                                Tab.REPORT -> WeeklyReport(
                                                    days = journal,
                                                    today = java.time.LocalDate.now(),
                                                    use = watchedUse,
                                                    onAskForUsageAccess = { askForUsageAccess() },
                                                    onReviewLimits = { tab = Tab.BLOCKS },
                                                    appTimesFor = { week -> appTimes(week.apps, week.screens) },
                                                    coffeeNudge = coffeeNudgeDue(),
                                                    onCoffee = { openCoffee() },
                                                    onNotNow = { stampCoffeeNudge() },
                                                    tapers = taperNotes(),
                                                    hard = hardTimes,
                                                )
                                                Tab.SETTINGS -> SettingsTab()
                                            }
                                        }
                                        if (onPage) Page(content = body) else Page(listScroll, body)
                                    }
                                }
                                Screen.UNLOCK -> Page {
                                    UnlockScreen(
                                        rules = rules,
                                        icons = appIcons,
                                        installedPackages = installedPackages,
                                        selectedPackage = selectedPackage,
                                        secondsRemaining = secondsRemaining,
                                        totalSeconds = waitSeconds,
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
        }
    }

    /** A tab's worth of content, scrolling on its own. */
    @Composable
    private fun Page(
        scroll: ScrollState = rememberScrollState(),
        content: @Composable ColumnScope.() -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }

    @Composable
    private fun BlocksTab() {
        val app = openApp
        if (showGrayscale) {
            GrayscaleOverview(
                rules = rules,
                icons = appIcons,
                installedPackages = installedPackages,
                graySettings = graySettings,
                grayCanWrite = grayCanWrite,
                changeDelaySeconds = changeDelaySeconds,
                suggestions = knownWindows(screenLimits, appLimits, graySettings),
                onChange = { id, setting -> requestGrayChange(id, setting) },
                onBack = { showGrayscale = false },
                labelFor = ::labelFor,
            )
        } else if (app == null) {
            BlocksList()
        } else {
            AppDetail(
                packageName = app,
                rules = rules,
                screenLimits = screenLimits,
                appLimits = appLimits,
                graySettings = graySettings,
                grayCanWrite = grayCanWrite,
                changeDelaySeconds = changeDelaySeconds,
                otherApps = otherApps(),
                remainingFor = ::remainingLabelFor,
                onChosen = { id, limits, gray -> requestEditorChange(id, limits, gray) },
                onGrayChosen = { id, setting -> requestGrayChange(id, setting) },
                onBack = { openApp = null },
                labelFor = ::labelFor,
                helpFor = ::helpFor,
            )
        }
    }

    @Composable
    private fun BlocksList() {
        var showDisclosure by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.width(12.dp))
            // The chip may wrap, so that a long word beside the name at a large
            // font size pushes onto a second line instead of squeezing the name.
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f, fill = false)) {
                    StatusChip(onFix = { showDisclosure = true })
                }
                QuickSettingsMenu(
                    rules = rules,
                    screenLimits = screenLimits,
                    onPreset = { name, ids -> requestPreset(name, ids) },
                )
            }
        }

        // The card's first item is the same way in as the red one, so the red
        // one steps aside while the card is up.
        val setupOpen = setupStage != SetupStage.CLOSED
        // Done once Doorman is on and a level has been chosen; from then on the
        // card has nothing left to say.
        LaunchedEffect(serviceEnabled, setupStage) {
            if (serviceEnabled && setupStage == SetupStage.LEVEL_CHOSEN) {
                prefs.setSetupStage(SetupStage.CLOSED)
            }
        }
        if (setupOpen) {
            SetupCard(
                rules = rules,
                screenLimits = screenLimits,
                serviceOn = serviceEnabled,
                stage = setupStage,
                notificationsOn = notificationsGranted,
                onEnable = { showDisclosure = true },
                onPickLevel = { name, ids -> requestPreset(name, ids) },
                onNotify = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onDismiss = { lifecycleScope.launch { prefs.setSetupStage(SetupStage.CLOSED) } },
            )
        } else if (!serviceEnabled) {
            DisabledCard(onEnable = { showDisclosure = true })
        }
        if (showDisclosure) {
            DisclosureDialog(
                onAgree = {
                    showDisclosure = false
                    // NEW_TASK matters: without it the settings screen
                    // lands inside Doorman's own task, and tapping the
                    // launcher icon afterwards reopens Android settings.
                    startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
                onDecline = { showDisclosure = false },
            )
        }

        activePass?.let { pass ->
            val minutesLeft =
                ((pass.expiresAt - System.currentTimeMillis()) / 60_000L).toInt().coerceAtLeast(0)
            if (pass.isActiveAt(System.currentTimeMillis())) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(
                                R.string.pass_active_banner,
                                labelFor(rules.appFor(pass.packageName)?.labelKey ?: ""),
                                minutesLeft + 1,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { lifecycleScope.launch { prefs.clearPass() } }) {
                            Text(stringResource(R.string.action_end_pass))
                        }
                    }
                }
            }
        }

        // Nothing is being held while the service is off, so a card saying what
        // is blocked now would be telling people something untrue.
        if (serviceEnabled) {
            RightNowCard(
                state = rightNow(heldTargets(), spent, java.time.LocalDateTime.now(), hardTimes),
                onGetPass = { openUnlock() },
                actions = {
                    HardTimeActions(
                        targets = heldTargets(),
                        hard = hardTimes,
                        today = java.time.LocalDate.now(),
                        onTakeDay = { lifecycleScope.launch { prefs.takeHardDay(java.time.LocalDate.now()) } },
                        onTakeWeek = { lifecycleScope.launch { prefs.takeHardWeek(java.time.LocalDate.now()) } },
                    )
                },
            )
        }

        GrayscaleRow(count = graySettings.size, onClick = { showGrayscale = true })

        AppList(
            rules = rules,
            screenLimits = screenLimits,
            appLimits = appLimits,
            graySettings = graySettings,
            otherApps = otherApps(),
            icons = appIcons,
            installedPackages = installedPackages,
            remainingFor = ::remainingLabelFor,
            onOpen = { pkg -> openApp = pkg },
            labelFor = ::labelFor,
        )
    }

    /**
     * Whether Doorman is working, at a glance, beside its name.
     *
     * A word as well as a colour: a green dot and a red one are the same dot to
     * somebody who cannot tell them apart. Off is a button, because the thing to
     * do about it is the first thing anyone will want.
     */
    @Composable
    private fun StatusChip(onFix: () -> Unit) {
        val on = serviceEnabled
        val colour = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .then(if (on) Modifier else Modifier.clickable(role = Role.Button, onClick = onFix))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (on) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = colour,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (on) R.string.status_chip_on else R.string.status_chip_off),
                style = MaterialTheme.typography.labelLarge,
                color = colour,
            )
        }
    }

    /**
     * The way in when Doorman is off.
     *
     * The only alarm colour in the app is here, and it is the only thing that
     * deserves one: with the service off Doorman is a settings screen that
     * blocks nothing, and a card the same colour as every other card is a card
     * people scroll past.
     */
    @Composable
    private fun DisabledCard(onEnable: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.status_disabled),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    stringResource(R.string.status_disabled_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onEnable,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.action_enable_doorman))
                }
            }
        }
    }

    /**
     * The prominent disclosure Google Play requires before an accessibility
     * service is requested. Only the agree button goes on to Android's
     * settings; declining leaves everything as it was.
     */
    @Composable
    private fun DisclosureDialog(onAgree: () -> Unit, onDecline: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDecline,
            title = { Text(stringResource(R.string.access_dialog_title)) },
            text = {
                Text(
                    stringResource(R.string.accessibility_service_description),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = onAgree) {
                    Text(stringResource(R.string.access_dialog_agree))
                }
            },
            dismissButton = {
                TextButton(onClick = onDecline) {
                    Text(stringResource(R.string.access_dialog_decline))
                }
            },
        )
    }

    private fun openUnlock() {
        screen = Screen.UNLOCK
        selectedPackage = null
        wasReset = false
        secondsRemaining = 0
    }

    /**
     * How Doorman behaves and what it is, which is not what it blocks.
     *
     * Everything here is set once and then lived with. It was in the way of the
     * thing people change often, and now it is one tap away instead.
     */
    @Composable
    private fun SettingsTab() {
        if (showDiagnostics) {
            SubPageHeader(
                title = stringResource(R.string.section_diagnostics),
                onBack = { showDiagnostics = false },
            )
            DiagnosticsSection(
                lastSeen = lastSeen,
                countdown = reportCountdown,
                reportReady = reportFile != null,
                captureFailed = reportFailed,
                onCapture = { captureReport() },
                onShare = { shareReport() },
            )
            return
        }
        Text(
            stringResource(R.string.tab_settings),
            style = MaterialTheme.typography.headlineMedium,
        )

        // The one setting that decides whether the others mean anything, so
        // it comes first and says what it will do.
        SectionHeader(stringResource(R.string.settings_group_safety))
        SurfaceCard {
            DelayRow(seconds = changeDelaySeconds, onChange = { seconds -> requestDelayChange(seconds) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp)) {
                SwitchRow(
                    label = stringResource(R.string.weekly_report_switch),
                    checked = weeklyReport,
                    onCheckedChange = { on ->
                        if (on) askToNotify()
                        lifecycleScope.launch { prefs.setWeeklyReport(on) }
                    },
                    help = stringResource(R.string.weekly_report_help),
                )
            }
        }

        SectionHeader(stringResource(R.string.settings_group_behaviour))
        SurfaceCard {
            RedirectSection(
                current = redirect,
                apps = allApps,
                icons = appIcons,
                onPick = { choice -> lifecycleScope.launch { prefs.setRedirect(choice) } },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LanguageSection(
                supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                currentTag = appLocaleTag(),
                onPick = { tag -> setAppLocale(tag) },
            )
        }

        SectionHeader(stringResource(R.string.settings_group_about))
        SurfaceCard {
            SupportRow(onCoffee = { openCoffee() })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // The walkthrough explains the change delay and the pass, which
            // are the two things people forget how to use. Somewhere to find
            // it again costs one row.
            NavRow(
                icon = Icons.Outlined.Refresh,
                title = stringResource(R.string.tutorial_replay),
                onClick = { lifecycleScope.launch { prefs.setTutorialSeen(false) } },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            NavRow(
                icon = Icons.Outlined.Build,
                title = stringResource(R.string.section_diagnostics),
                onClick = { showDiagnostics = true },
            )
        }
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
        val loosening = isLoosening(current, limits, java.time.LocalDate.now(), hardTimes)
        if (!loosening || changeDelaySeconds <= 0) {
            lifecycleScope.launch {
                prefs.setLimits(targetId, limits)
                // Recorded where the change actually lands, not where it is
                // asked for: a change that sits through the delay and is walked
                // away from never happened, and a report that counted it would
                // be telling somebody they gave in when they did not.
                if (loosening) prefs.recordToday(loosenings = 1)
            }
            return
        }
        startPending(PendingChange.Mode(targetId, current, limits))
    }

    /**
     * What the editor saves: limits and grey together, as the user saw them.
     *
     * Each half is judged on its own, as it always was: tightening lands at
     * once and loosening waits. Only when both would wait do they share one
     * countdown, since two dialogs in a row is two waits for one decision.
     */
    private fun requestEditorChange(targetId: String, limits: Limits, gray: GraySetting?) {
        val currentLimits = screenLimits[targetId] ?: appLimits[targetId] ?: Limits.OFF
        val currentGray = graySettings[targetId]
        val limitsChanged = limits != currentLimits
        val grayChanged = gray != currentGray
        val waits = changeDelaySeconds > 0 &&
            limitsChanged && isLoosening(currentLimits, limits, java.time.LocalDate.now(), hardTimes) &&
            grayChanged && GrayPolicy.isLoosening(currentGray, gray)
        if (waits) {
            startPending(PendingChange.Both(targetId, currentLimits, limits, currentGray, gray))
            return
        }
        if (limitsChanged) requestLimitsChange(targetId, limits)
        if (grayChanged) requestGrayChange(targetId, gray)
    }

    /**
     * Grey on, or more of it, applies at once; grey off, or less of it, waits
     * like any other block being loosened.
     */
    private fun requestGrayChange(targetId: String, setting: GraySetting?) {
        val loosening = GrayPolicy.isLoosening(graySettings[targetId], setting)
        if (!loosening || changeDelaySeconds <= 0) {
            lifecycleScope.launch {
                prefs.setGrayscale(targetId, setting)
                if (loosening) prefs.recordToday(loosenings = 1)
            }
            return
        }
        startPending(PendingChange.Gray(targetId, graySettings[targetId], setting))
    }

    /** What a limit's or a grey setting's target is called: its screen's label, or its app's. */
    private fun targetLabelFor(targetId: String): String {
        val screenLabel = labelKeyFor(targetId).let(::labelFor)
        return if (screenLabel.isNotEmpty() && screenLabel != targetId) {
            screenLabel
        } else {
            rules.apps[targetId]?.labelKey?.let(::labelFor) ?: appLabelFor(targetId)
        }
    }

    /**
     * Ticks the setup card's second item. Recorded where a preset lands rather
     * than where it is tapped, so one that is still waiting out the change delay
     * has not been chosen yet.
     */
    private suspend fun noteLevelChosen() {
        if (setupStage == SetupStage.OPEN) prefs.setSetupStage(SetupStage.LEVEL_CHOSEN)
    }

    private fun requestPreset(name: String, ids: Set<String>) {
        val weakens = screenLimits.any { (id, limits) -> !limits.isOff && id !in ids }
        if (!weakens || changeDelaySeconds <= 0) {
            lifecycleScope.launch {
                prefs.setEnabledScreens(ids, screenDefaults.keys)
                if (weakens) prefs.recordToday(loosenings = 1)
                noteLevelChosen()
            }
            return
        }
        startPending(PendingChange.Preset(name, ids))
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
        pendingTotalSeconds = changeDelaySeconds
        pendingCountdown = lifecycleScope.launch {
            while (pendingSeconds > 0) {
                delay(1_000)
                pendingSeconds -= 1
            }
            applyPendingChange()
        }
    }

    private fun applyPendingChange() {
        // Anything that waited, waited because it weakened something. Surviving
        // the wait is exactly the moment worth recording.
        when (val change = pendingChange) {
            is PendingChange.Mode ->
                lifecycleScope.launch {
                    prefs.setLimits(change.id, change.limits)
                    prefs.recordToday(loosenings = 1)
                }
            is PendingChange.Preset ->
                lifecycleScope.launch {
                    prefs.setEnabledScreens(change.ids, screenDefaults.keys)
                    prefs.recordToday(loosenings = 1)
                    noteLevelChosen()
                }
            is PendingChange.Both ->
                lifecycleScope.launch {
                    prefs.setLimits(change.id, change.limits)
                    prefs.setGrayscale(change.id, change.setting)
                    // Two loosenings, as two saves would have been.
                    prefs.recordToday(loosenings = 2)
                }
            is PendingChange.Gray ->
                lifecycleScope.launch {
                    prefs.setGrayscale(change.id, change.setting)
                    prefs.recordToday(loosenings = 1)
                }
            is PendingChange.Delay ->
                lifecycleScope.launch { prefs.setChangeDelaySeconds(change.seconds) }
            null -> Unit
        }
        pendingChange?.let { change ->
            lifecycleScope.launch {
                snackbar.showSnackbar(getString(R.string.pending_applied, pendingTargetName(change)))
            }
        }
        pendingChange = null
        pendingSeconds = 0
    }

    /** What the countdown is for, said in a line. */
    private fun pendingTitle(change: PendingChange, seconds: Int): String = when (change) {
        is PendingChange.Mode -> getString(R.string.pending_change_screen, targetLabelFor(change.id), seconds)
        is PendingChange.Gray -> getString(R.string.pending_change_screen, targetLabelFor(change.id), seconds)
        is PendingChange.Both -> getString(R.string.pending_change_screen, targetLabelFor(change.id), seconds)
        is PendingChange.Preset -> getString(R.string.preset_applying, change.name, seconds)
        is PendingChange.Delay -> getString(R.string.pending_change_delay, seconds)
    }

    /** From what to what, one line for each thing that is changing. */
    private fun pendingDetails(change: PendingChange): List<String> = when (change) {
        is PendingChange.Mode -> listOf(limitsFromTo(change.from, change.limits))
        is PendingChange.Gray -> listOf(grayFromTo(change.from, change.setting))
        is PendingChange.Both -> listOf(
            limitsFromTo(change.fromLimits, change.limits),
            grayFromTo(change.fromGray, change.setting),
        )
        is PendingChange.Preset, is PendingChange.Delay -> emptyList()
    }

    private fun limitsFromTo(from: Limits, to: Limits): String =
        getString(R.string.pending_change_from_to, summaryText(from), summaryText(to))

    private fun grayFromTo(from: GraySetting?, to: GraySetting?): String = getString(
        R.string.pending_change_from_to,
        graySummaryText(resources, from),
        graySummaryText(resources, to),
    )

    /** What changed, for the note that says it has. */
    private fun pendingTargetName(change: PendingChange): String = when (change) {
        is PendingChange.Mode -> targetLabelFor(change.id)
        is PendingChange.Gray -> targetLabelFor(change.id)
        is PendingChange.Both -> targetLabelFor(change.id)
        is PendingChange.Preset -> change.name
        is PendingChange.Delay -> getString(R.string.section_change_delay)
    }

    private fun cancelPendingChange() {
        pendingCountdown?.cancel()
        pendingCountdown = null
        pendingChange = null
        pendingSeconds = 0
    }

    /**
     * Everything that can be holding something, named for a sentence: "Instagram
     * · Reels", or "YouTube" for a whole app.
     */
    private fun heldTargets(): List<HeldTarget> {
        val screens = rules.apps.flatMap { (packageName, app) ->
            val appName = labelFor(app.labelKey)
            listOf(HeldTarget(packageName, appName, appLimits[packageName] ?: Limits.OFF)) +
                app.screens.map {
                    HeldTarget(
                        it.id,
                        "$appName · ${briefLabelFor(it.labelKey)}",
                        screenLimits[it.id] ?: Limits.OFF,
                        app = appName,
                        screen = true,
                    )
                }
        }
        val others = otherApps().map {
            HeldTarget(it.packageName, it.label, appLimits[it.packageName] ?: Limits.OFF)
        }
        return screens + others
    }

    private fun remainingLabelFor(targetId: String): String? {
        val limits = screenLimits[targetId] ?: appLimits[targetId] ?: return null
        val today = java.time.LocalDateTime.now()
        val applicable = limits.allowancesOn(today.dayOfWeek)
        // A limit that skips today has to say so, or it reads as a limit that
        // is not working.
        if (applicable.isEmpty()) {
            return if (limits.allowances.isEmpty()) null else getString(R.string.remaining_not_today)
        }
        val left = applicable.minOf { allowance ->
            cat.doorman.app.limits.Allowances.remainingMillis(
                allowance,
                spent[cat.doorman.app.limits.Allowances.ledgerKey(targetId, allowance.period)]
                    ?: cat.doorman.app.limits.Allowances.Spent.NOTHING,
                today,
                hardTimes,
            )
        }
        if (left <= 0L) return getString(R.string.remaining_none)
        val minutes = Math.ceil(left / 60_000.0).toInt()
        return getString(R.string.remaining_left, durationText(resources, minutes))
    }

    /**
     * What a set of limits says, outside a composable, for the pending-change
     * dialog. Deliberately terse: the dialog is a countdown, not a manual.
     */
    private fun summaryText(limits: Limits): String =
        limitsSummaryText(resources, limits, hardTimes, withRelief = false)

    /** Every limit that lowers itself, with the screen or app it belongs to, for the report. */
    private fun taperNotes(): List<TaperNote> = heldTargets().flatMap { target ->
        target.limits.allowances.filter { it.taper != null }.map { TaperNote(target.name, it) }
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
        // while you are elsewhere. Coming back says so: the dialog is simply
        // gone, and nothing else would tell anyone the change did not happen.
        if (pendingChange != null) droppedWhileAway = true
        cancelPendingChange()
        if (screen == Screen.UNLOCK && selectedPackage != null && secondsRemaining > 0) {
            wasReset = true
            secondsRemaining = waitSeconds
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openReportIfAsked(intent)
    }

    /**
     * The notification's only job is to get somebody to the report, so tapping
     * it lands on the report and not on wherever they happened to leave the app.
     */
    private fun openReportIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(WeeklyReportNotifier.EXTRA_OPEN_REPORT, false) != true) return
        screen = Screen.HOME
        tab = Tab.REPORT
    }

    /**
     * Read at the moment the report is drawn, not collected in the background.
     *
     * Nothing is cached across launches on purpose: a figure Doorman keeps is a
     * figure Doorman has to be trusted with, and this one it can simply ask
     * Android for again.
     */
    private fun refreshUsage() {
        watchedUse = UsageAccess.lastTwoWeeks(
            context = this,
            packages = rules.supportedPackages + appLimits.keys,
            today = java.time.LocalDate.now(),
        )
    }

    /**
     * Once a season, and only once there is a month of use to have an opinion
     * about. Never a notification, never a dialog: it is a card at the bottom
     * of a screen the user chose to open.
     */
    private fun coffeeNudgeDue(): Boolean {
        if (journal.size < COFFEE_NUDGE_MIN_DAYS_RECORDED) return false
        val last = coffeeNudgedOn?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            ?: return true
        return java.time.temporal.ChronoUnit.DAYS.between(last, java.time.LocalDate.now()) >= COFFEE_NUDGE_DAYS
    }

    private fun stampCoffeeNudge() {
        lifecycleScope.launch { prefs.setCoffeeNudgedOn(java.time.LocalDate.now().toString()) }
    }

    /**
     * Names for the report's screen-time card. Screens under a minute are
     * dropped: a "Reels 0 min" line next to a blocked Reels would read as the
     * block leaking.
     */
    private fun appTimes(apps: Map<String, Long>, screens: Map<String, Long>): List<AppTime> =
        apps.entries
            .filter { it.value >= REPORTABLE_MILLIS }
            .sortedByDescending { it.value }
            .map { (pkg, millis) ->
                // Several rules can describe one place: Reels in the tab and
                // Reels opened from anywhere are two rules and one screen to
                // the person reading this. Equal names are summed rather than
                // listed twice, and the total is judged after summing so two
                // half-minutes are not both rounded away.
                val named = rules.apps[pkg]?.screens.orEmpty()
                    .mapNotNull { screen ->
                        val ms = screens[screen.id] ?: return@mapNotNull null
                        briefLabelFor(screen.labelKey) to ms
                    }
                    .groupBy({ it.first }, { it.second })
                    .map { (label, times) -> label to times.sum() }
                    .filter { it.second >= REPORTABLE_MILLIS }
                    .sortedByDescending { it.second }
                AppTime(label = rules.appFor(pkg)?.let { labelFor(it.labelKey) } ?: appLabelFor(pkg), millis = millis, screens = named)
            }

    /**
     * A screen's name as a noun, for a report.
     *
     * The labels in rules.json are written for a switch -- "Open stories",
     * "Keep swiping from a reel someone sent" -- which is right beside a
     * toggle and wrong in a sentence about time. Where a `_brief` string
     * exists it wins; where none does, the switch label is still better than
     * nothing.
     */
    @SuppressLint("DiscouragedApi")
    private fun briefLabelFor(labelKey: String): String {
        val id = resources.getIdentifier(labelKey + "_brief", "string", packageName)
        return if (id != 0) getString(id) else labelFor(labelKey)
    }

    private fun askForUsageAccess() {
        // No dialog exists for this one; the most an app may do is open the
        // list in Android's settings and let the user find it there.
        runCatching { startActivity(UsageAccess.settingsIntent()) }
    }

    private fun askToNotify() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (hasAskedToNotify) return
        hasAskedToNotify = true
        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        // Usage access is granted in Android's settings, so coming back to
        // Doorman is the only moment it can have changed.
        refreshUsage()
        serviceEnabled = isAccessibilityServiceEnabled(this)
        notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        // Granted from a computer, so coming back to Doorman is when it shows.
        val grayscale = GrayscaleController(this)
        grayCanWrite = grayscale.canWrite
        // With the service off nothing is left to put colour back when grey's
        // hours end, and a force stop leaves the screen grey with nobody to
        // blame. Doorman is open now, so it does it.
        if (!serviceEnabled && grayCanWrite) grayscale.restoreIfOurs()
        if (droppedWhileAway) {
            droppedWhileAway = false
            lifecycleScope.launch { snackbar.showSnackbar(getString(R.string.pending_cancelled_left)) }
        }
        lastSeen = DoormanAccessibilityService.instance?.lastSeen()
        watchServiceState()
        if (screen == Screen.UNLOCK && selectedPackage != null && secondsRemaining > 0) {
            startCountdown()
        }
    }

    override fun onPause() {
        super.onPause()
        val manager = getSystemService(AccessibilityManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            servicesListener?.let { manager.removeAccessibilityServicesStateChangeListener(it) }
        }
        stateListener?.let { manager.removeAccessibilityStateChangeListener(it) }
        servicesListener = null
        stateListener = null
    }

    /**
     * Keeps the status card honest while the app is open.
     *
     * Reading once in onResume is a moment too early after an update: the
     * system drops accessibility services while it replaces the package and
     * re-binds them a second or two later, so an app already in the
     * foreground would sit there in alarm red saying Doorman was off when it
     * was seconds from being on. It also catches somebody switching the
     * service off from Android's settings on another screen.
     */
    private fun watchServiceState() {
        val manager = getSystemService(AccessibilityManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && servicesListener == null) {
            // Fires when the set of enabled services changes, which is the
            // exact question being asked. Only from Android 13.
            val listener = AccessibilityManager.AccessibilityServicesStateChangeListener {
                serviceEnabled = isAccessibilityServiceEnabled(this)
            }
            servicesListener = listener
            manager.addAccessibilityServicesStateChangeListener(listener)
        }
        if (stateListener == null) {
            // Coarser -- it only reports accessibility being switched on or
            // off as a whole -- but it is all there is before Android 13.
            val listener = AccessibilityManager.AccessibilityStateChangeListener {
                serviceEnabled = isAccessibilityServiceEnabled(this)
            }
            stateListener = listener
            manager.addAccessibilityStateChangeListener(listener)
        }
    }

    private var servicesListener: AccessibilityManager.AccessibilityServicesStateChangeListener? = null
    private var stateListener: AccessibilityManager.AccessibilityStateChangeListener? = null

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
