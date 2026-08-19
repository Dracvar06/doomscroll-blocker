package cat.doorman.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
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

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    private var serviceEnabled by mutableStateOf(false)
    private var rules by mutableStateOf(RuleSet())
    private var screenDefaults: Map<String, Boolean> = emptyMap()
    private var enabledScreens by mutableStateOf(emptySet<String>())
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = Prefs(this)
        rules = RuleLoader.load(this)
        screenDefaults = RuleLoader.screenDefaults(rules)

        lifecycleScope.launch {
            prefs.enabledScreenIds(screenDefaults).collectLatest { enabledScreens = it }
        }
        lifecycleScope.launch { prefs.activePass.collectLatest { activePass = it } }
        lifecycleScope.launch { prefs.waitSeconds.collectLatest { waitSeconds = it } }
        lifecycleScope.launch { prefs.passMinutes.collectLatest { passMinutes = it } }

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
                                labelFor(rules.apps[pass.packageName]?.labelKey ?: ""),
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
            enabledScreenIds = enabledScreens,
            onScreenToggled = { id, on ->
                lifecycleScope.launch { prefs.setScreenEnabled(id, on) }
            },
            onPreset = { ids ->
                lifecycleScope.launch { prefs.setEnabledScreens(ids, screenDefaults.keys) }
            },
            labelFor = ::labelFor,
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
