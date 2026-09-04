package cat.doorman.app.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import cat.doorman.app.R

/**
 * How long something is, said two ways.
 *
 * Budgets used to be minutes and nothing else, so "%d minutes" was the whole
 * story. Now that a weekly allowance can run to forty-two hours, "2520 minutes"
 * is a number nobody can read at a glance, and the same duration has to be able
 * to appear in a sentence and in the middle of a dial without either one
 * reading badly.
 */

/**
 * The prose form: "45 minutes", "12 h", "2 h 30 min". For summaries and for
 * telling someone what they have left.
 */
fun durationText(res: Resources, totalMinutes: Int): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0 -> res.getQuantityString(R.plurals.minutes, minutes, minutes)
        minutes == 0 -> res.getString(R.string.duration_hours, hours)
        else -> res.getString(R.string.duration_hours_minutes, hours, minutes)
    }
}

// LocalResources, not LocalContext.current.resources: the latter is not
// configuration-aware, and Doorman has a language setting of its own, so
// changing it would leave these labels in the language before last.
@Composable
fun durationLabel(totalMinutes: Int): String = durationText(LocalResources.current, totalMinutes)

/**
 * The terse form for the middle of a dial: "45 min", "12 h", "1 h 30".
 *
 * A dial is read while a thumb is on it, and the word "minutes" spelled out
 * fills the ring and pushes the number down to a size that cannot be read
 * mid-drag. The unit is already implied by the control.
 */
@Composable
fun dialLabel(totalMinutes: Int): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0 -> stringResource(R.string.duration_minutes, minutes)
        minutes == 0 -> stringResource(R.string.duration_hours, hours)
        else -> stringResource(R.string.duration_hours_short, hours, minutes)
    }
}
