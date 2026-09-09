package cat.doorman.app.report

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cat.doorman.app.data.Prefs
import cat.doorman.app.service.AccessibilityStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Runs once, right after Doorman has been updated.
 *
 * Two things do not reliably survive an update. Alarms are cleared with the
 * old process, so the weekly report is rebooked. And on some devices the
 * accessibility service comes back switched off -- which for this app means
 * everything is silently open again, and the user has no reason to suspect it
 * until the feed is already in front of them. One notification, only when the
 * service is actually off, tapping straight to the switch.
 */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        val finish = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (Prefs(app).weeklyReport.first()) WeeklyReportNotifier.schedule(app)
                // The system re-binds accessibility services after an update,
                // and the registry is empty while it does. How long that takes
                // is the device's business, not ours, and waiting for the
                // slowest case would delay a real warning past the point of
                // being useful. So this looks early and is allowed to be
                // wrong: the service withdraws the notice itself when it
                // connects. See WeeklyReportNotifier.clearServiceOff.
                delay(SETTLE_MILLIS)
                if (!AccessibilityStatus.isEnabled(app)) WeeklyReportNotifier.notifyServiceOff(app)
            } finally {
                finish.finish()
            }
        }
    }
}

private const val SETTLE_MILLIS = 5_000L
