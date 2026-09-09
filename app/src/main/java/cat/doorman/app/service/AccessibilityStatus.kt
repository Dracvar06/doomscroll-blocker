package cat.doorman.app.service

import android.content.Context
import cat.doorman.app.ui.isAccessibilityServiceEnabled

/**
 * Whether Doorman's accessibility service is actually on.
 *
 * Delegates to the registry-based check rather than parsing the secure
 * setting: after a reinstall the persisted string can still name the service
 * while the system has dropped it, which is the one case a post-update check
 * exists to catch. See [isAccessibilityServiceEnabled].
 */
object AccessibilityStatus {
    fun isEnabled(context: Context): Boolean = isAccessibilityServiceEnabled(context)
}
