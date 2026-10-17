package cat.doorman.app.block

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import cat.doorman.app.BuildConfig
import cat.doorman.app.R
import cat.doorman.app.rules.OverlayBounds

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
    private var shownBand: OverlayBounds.Band? = null

    val isShowing: Boolean get() = overlay != null

    /**
     * Debug builds only: decisions are still made and logged, but nothing is
     * drawn. It is how a screen that is blocked can be driven and dumped from
     * adb, since a block swallows the taps meant for what is under it.
     */
    var debugSuppress = false
        set(value) {
            field = if (BuildConfig.DEBUG) value else false
            if (field) hide()
        }

    /**
     * [band] is the slice of screen to cover. It stops short of the search bar
     * above and the tab bar below, so the user can still look someone up or
     * walk to their messages rather than being cornered into switching Doorman
     * off. A band with a null height covers everything below its top, which is
     * the safe fallback when those controls cannot be located.
     */
    fun show(
        screenId: String,
        screenLabel: String,
        band: OverlayBounds.Band = OverlayBounds.Band(0, null),
        bodyRes: Int = R.string.blocked_body,
    ): Boolean {
        if (debugSuppress) return false
        if (shownForScreenId == screenId && shownBand == band && overlay != null) return false
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
            band.heightPx ?: WindowManager.LayoutParams.MATCH_PARENT,
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
            y = band.topPx
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

        return runCatching { windowManager.addView(view, params) }
            .onSuccess {
                overlay = view
                shownForScreenId = screenId
                shownBand = band
                Log.i(TAG, "overlay shown for $screenId")
            }
            .onFailure { Log.e(TAG, "overlay failed for $screenId", it) }
            .isSuccess
    }

    private var cover: TextView? = null
    private var coverBounds: Rect? = null

    val isCoverShowing: Boolean get() = cover != null

    /**
     * Covers an app's floating window, wherever it has been dragged to.
     *
     * Not touchable, on purpose: the user must still be able to drag it away,
     * close it or tap it open -- which lands on the full app, where the normal
     * block takes over. A cover that swallowed touches would trap the window on
     * screen with no way to get rid of it.
     */
    fun showCover(bounds: Rect, label: String) {
        if (debugSuppress) return
        val existing = cover
        if (existing != null) {
            if (coverBounds != bounds) {
                coverBounds = Rect(bounds)
                runCatching { windowManager.updateViewLayout(existing, coverParams(bounds)) }
            }
            return
        }
        val view = TextView(service).apply {
            setBackgroundColor(0xFF0F1A16.toInt())
            setTextColor(0xFFB7C9C0.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            text = label
        }
        runCatching { windowManager.addView(view, coverParams(bounds)) }
            .onSuccess {
                cover = view
                coverBounds = Rect(bounds)
                Log.i(TAG, "cover shown at ${bounds.flattenToString()}")
            }
            .onFailure { Log.e(TAG, "cover failed", it) }
    }

    fun hideCover() {
        cover?.let {
            runCatching { windowManager.removeView(it) }
            Log.i(TAG, "cover hidden")
        }
        cover = null
        coverBounds = null
    }

    private fun coverParams(bounds: Rect) = WindowManager.LayoutParams(
        bounds.width(),
        bounds.height(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = bounds.left
        y = bounds.top
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
        }
    }

    fun hide() {
        overlay?.let {
            runCatching { windowManager.removeView(it) }
            Log.i(TAG, "overlay hidden")
        }
        overlay = null
        shownForScreenId = null
        shownBand = null
    }

    private companion object {
        const val TAG = "Doorman"
    }
}
