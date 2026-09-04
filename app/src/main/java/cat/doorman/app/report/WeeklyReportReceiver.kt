package cat.doorman.app.report

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cat.doorman.app.data.Prefs
import cat.doorman.app.limits.Journal
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Monday, nine o'clock.
 *
 * Rebooks itself before anything else can go wrong, so a week that produces no
 * notification -- the switch was off, the week was empty -- still leaves next
 * Monday booked. An alarm that only survives when it has something to say would
 * fall silent the first quiet week and never come back.
 */
class WeeklyReportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        WeeklyReportNotifier.schedule(app)

        val finish = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val prefs = Prefs(app)
                if (!prefs.weeklyReport.first()) return@launch

                val (justGone, _) = Journal.lastTwoWeeks(prefs.journal.first(), LocalDate.now())
                // A week with nothing in it is a week Doorman has nothing to
                // say about. Saying it anyway would be noise dressed as news.
                if (justGone.isEmpty) return@launch

                WeeklyReportNotifier.notify(app, justGone)
            } finally {
                finish.finish()
            }
        }
    }
}
