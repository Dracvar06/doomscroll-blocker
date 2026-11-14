package cat.doorman.app.gray

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Switches the whole display to grey, and back.
 *
 * Android gives an app no way to desaturate the screen: an overlay can cover
 * what is under it but cannot recolour it. The one thing that can is the
 * phone's own colour correction, in monochromacy mode, and that is a protected
 * setting -- it needs WRITE_SECURE_SETTINGS, which only a computer can grant,
 * once, with adb. Without it every call here does nothing and says so.
 *
 * Whatever the user had in those two settings before is written down the first
 * time grey goes on and put back when it comes off. Somebody who already uses
 * colour correction for their eyes must not find it changed for good.
 *
 * Grey left on after a crash would be a nasty way to learn the app had died, so
 * [restoreIfOurs] is called whenever the service starts or stops.
 */
class GrayscaleController(private val context: Context) {

    private val resolver get() = context.contentResolver

    // Plain SharedPreferences, read and written synchronously: this runs when the
    // service is being torn down, where waiting on DataStore is not an option.
    private val memory = context.getSharedPreferences("grayscale_state", Context.MODE_PRIVATE)

    /** What this controller last asked for, so a screen that stays grey is not rewritten. */
    private var applied: Boolean? = null

    val canWrite: Boolean
        get() = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Grey now when [on], colour now otherwise. Returns false if the setting could not be written. */
    fun set(on: Boolean): Boolean {
        if (applied == on) return true
        if (!canWrite) {
            applied = null
            return false
        }
        return runCatching { if (on) turnOn() else turnOff() }
            .onSuccess { applied = on }
            .onFailure { Log.w(TAG, "could not change the colour correction", it) }
            .isSuccess
    }

    /** Puts colour back if Doorman was the one that took it away. Safe to call any time. */
    fun restoreIfOurs() {
        applied = null
        if (!memory.getBoolean(KEY_OURS, false) || !canWrite) return
        runCatching { turnOff() }
            .onFailure { Log.w(TAG, "could not restore the colour correction", it) }
        applied = false
    }

    private fun turnOn() {
        val enabled = Settings.Secure.getInt(resolver, ENABLED, 0)
        val mode = Settings.Secure.getInt(resolver, MODE, UNSET)
        if (!memory.getBoolean(KEY_OURS, false)) {
            if (enabled == 1 && mode == MONOCHROMACY) {
                // Already grey by the user's own choice: leave it exactly so, and
                // do not claim it, or turning grey "off" would undo their setting.
                return
            }
            memory.edit()
                .putBoolean(KEY_OURS, true)
                .putInt(KEY_WAS_ENABLED, enabled)
                .putInt(KEY_WAS_MODE, mode)
                .commit()
        }
        Settings.Secure.putInt(resolver, MODE, MONOCHROMACY)
        Settings.Secure.putInt(resolver, ENABLED, 1)
    }

    private fun turnOff() {
        if (!memory.getBoolean(KEY_OURS, false)) return
        val wasEnabled = memory.getInt(KEY_WAS_ENABLED, 0)
        val wasMode = memory.getInt(KEY_WAS_MODE, UNSET)
        // Mode first, so colour never flashes to a half-restored state.
        Settings.Secure.putInt(resolver, MODE, wasMode)
        Settings.Secure.putInt(resolver, ENABLED, wasEnabled)
        memory.edit().putBoolean(KEY_OURS, false).commit()
    }

    companion object {
        const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"

        /** What to type once, on a computer, to let Doorman do this. */
        const val GRANT_COMMAND =
            "adb shell pm grant cat.doorman.app android.permission.WRITE_SECURE_SETTINGS"

        private const val TAG = "Doorman"

        // Hidden constants of Settings.Secure, spelled out: they have been stable for a decade.
        private const val ENABLED = "accessibility_display_daltonizer_enabled"
        private const val MODE = "accessibility_display_daltonizer"

        /** AccessibilityManager.DALTONIZER_SIMULATE_MONOCHROMACY. */
        private const val MONOCHROMACY = 0

        /** AccessibilityManager.DALTONIZER_DISABLED, which is also what an unset mode reads as. */
        private const val UNSET = -1

        private const val KEY_OURS = "ours"
        private const val KEY_WAS_ENABLED = "was_enabled"
        private const val KEY_WAS_MODE = "was_mode"
    }
}
