package cat.doorman.app.ui

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * The coffee link, and the whole of Doorman's business model.
 *
 * Doorman is free because the apps it competes with charge for "block only
 * Reels", which its author considers abusive. So the ask is made three times
 * and never louder than a text button: once at the end of the walkthrough, once
 * in Settings where it can be found on purpose, and once a season in the report
 * for people who have been using it long enough to know whether it helped. Not
 * as a notification, not as a dialog, never in the way of a block.
 */
const val COFFEE_URL = "https://buymeacoffee.com/eloiprat"

/** How long to leave somebody alone between reminders. About a season. */
const val COFFEE_NUDGE_DAYS = 56L

/** And how much use they should have had before the first one is worth making. */
const val COFFEE_NUDGE_MIN_DAYS_RECORDED = 28

/**
 * Hands the link to whatever browser the user has. Doorman has no network
 * permission and opens nothing itself.
 */
fun Context.openCoffee() {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, COFFEE_URL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
