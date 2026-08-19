package cat.doorman.app.block

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import cat.doorman.app.R

/**
 * Puts an opaque screen over a blocked feed.
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY, added from the service's own WindowManager.
 * That needs no SYSTEM_ALERT_WINDOW grant, and it sidesteps the
 * background-activity-launch restrictions a full-screen Activity would hit.
 *
 * Plain views rather than Compose: Compose inside a WindowManager window needs
 * ViewTreeLifecycleOwner and SavedStateRegistryOwner wired up by hand, which is
 * a lot of machinery for one static screen.
 */
class BlockOverlayController(private val service: AccessibilityService) {

    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlay: View? = null
    private var shownForScreenId: String? = null
    private var shownHeightPx: Int? = null

    val isShowing: Boolean get() = overlay != null

    /**
     * [coverHeightPx] limits the overlay to the top of the app's tab bar, so
     * that bar stays tappable and the user can walk to their messages instead of
     * being cornered. Null covers the whole screen, which is the safe fallback
     * when the tab bar cannot be located.
     */
    fun show(
        screenId: String,
        screenLabel: String,
        coverHeightPx: Int? = null,
        bodyRes: Int = R.string.blocked_body,
    ) {
        if (shownForScreenId == screenId && shownHeightPx == coverHeightPx && overlay != null) return
        hide()

        // Null root is right here: the view's layout params come from
        // WindowManager, not from a parent.
        @android.annotation.SuppressLint("InflateParams")
        val view = LayoutInflater.from(service).inflate(R.layout.overlay_block, null)
        view.findViewById<TextView>(R.id.blocked_title).text =
            service.getString(R.string.blocked_title, screenLabel)
        view.findViewById<TextView>(R.id.blocked_body).setText(bodyRes)
        view.findViewById<Button>(R.id.blocked_go_back).setOnClickListener {
            // Dismiss first: the app underneath needs a frame to settle on the
            // previous screen, and leaving the overlay up during that makes the
            // block look stuck.
            hide()
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            coverHeightPx ?: WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Touchable, so it swallows every tap and swipe meant for the feed,
            // but not focusable, so it never steals the keyboard or the back key.
            //
            // FLAG_LAYOUT_IN_SCREEN is what makes the height meaningful. Without
            // it the window is laid out inside the content area, below the status
            // bar, while the tab-bar coordinate we measured comes from
            // getBoundsInScreen and is absolute. The two origins differ by the
            // status bar height, which is just enough for the overlay to swallow
            // the tab bar it was supposed to leave alone.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP
            y = 0
            // The measured tab-bar coordinate is absolute, so the window has to
            // start at absolute zero for a height to mean the same thing. Left
            // to itself the frame is inset below the status bar -- measured at
            // y=173 on a Pixel 10 -- and a height that should stop above the tab
            // bar instead runs 173px past it, covering the very control the user
            // needs to reach their messages.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }

        runCatching { windowManager.addView(view, params) }
            .onSuccess {
                overlay = view
                shownForScreenId = screenId
                shownHeightPx = coverHeightPx
                Log.i(TAG, "overlay shown for $screenId")
            }
            .onFailure { Log.e(TAG, "overlay failed for $screenId", it) }
    }

    fun hide() {
        overlay?.let {
            runCatching { windowManager.removeView(it) }
            Log.i(TAG, "overlay hidden")
        }
        overlay = null
        shownForScreenId = null
        shownHeightPx = null
    }

    private companion object {
        const val TAG = "Doorman"
    }
}
