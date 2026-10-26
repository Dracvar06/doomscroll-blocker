package cat.doorman.app.block

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.graphics.withTranslation
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
     * Told when a block appears and when it goes. A block redrawn for the same
     * screen -- its band measured a few pixels differently -- is the same block
     * still standing, and is not reported again: how long a block has held
     * someone is counted from these.
     */
    interface Listener {
        fun onBlockShown()
        fun onBlockHidden()
        fun onRedirectTapped()
    }

    var listener: Listener? = null

    /**
     * What the second button on the block says -- "Go to Play Books" -- or
     * null for no second button, when there is nowhere to send anyone.
     */
    var redirectLabel: String? = null
        set(value) {
            field = value
            overlay?.let(::bindRedirect)
        }

    private fun bindRedirect(view: View) {
        val button = view.findViewById<Button>(R.id.blocked_redirect)
        val label = redirectLabel
        button.visibility = if (label == null) View.GONE else View.VISIBLE
        button.text = label
        button.setOnClickListener { listener?.onRedirectTapped() }
    }

    /**
     * Debug builds only: decisions are still made and logged, but nothing is
     * drawn. It is how a screen that is blocked can be driven and dumped from
     * adb, since a block swallows the taps meant for what is under it.
     */
    var debugSuppress = false
        set(value) {
            field = if (BuildConfig.DEBUG) value else false
            if (field) {
                hide()
                hideEntryCovers()
            }
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
        val sameBlock = overlay != null && shownForScreenId == screenId
        if (sameBlock) removeOverlay() else hide()

        // Null root is right here: the view's layout params come from
        // WindowManager, not from a parent.
        @android.annotation.SuppressLint("InflateParams")
        val view = LayoutInflater.from(service).inflate(R.layout.overlay_block, null)
        view.findViewById<TextView>(R.id.blocked_title).text =
            service.getString(R.string.blocked_title, screenLabel)
        view.findViewById<TextView>(R.id.blocked_body).setText(bodyRes)
        bindRedirect(view)
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
                if (!sameBlock) listener?.onBlockShown()
            }
            .onFailure { Log.e(TAG, "overlay failed for $screenId", it) }
            .isSuccess
    }

    fun hide() {
        val wasShowing = overlay != null
        removeOverlay()
        if (wasShowing) listener?.onBlockHidden()
    }

    private fun removeOverlay() {
        overlay?.let {
            runCatching { windowManager.removeView(it) }
            Log.i(TAG, "overlay hidden")
        }
        overlay = null
        shownForScreenId = null
        shownBand = null
    }

    // ------------------------------------------------------------ entry covers

    private val entryCovers = mutableMapOf<cat.doorman.app.rules.EntryButtons.Box, View>()

    /**
     * Covers the buttons into blocked screens -- Instagram's Reels tab -- with
     * a patch that takes the tap and shows a shut door. Called on every
     * evaluation; patches already where they should be are left alone, so the
     * bar does not flicker while a video plays above it.
     */
    fun showEntryCovers(boxes: List<cat.doorman.app.rules.EntryButtons.Box>, description: String) {
        if (debugSuppress) {
            hideEntryCovers()
            return
        }
        val wanted = boxes.toSet()
        (entryCovers.keys - wanted).forEach { box ->
            entryCovers.remove(box)?.let { runCatching { windowManager.removeView(it) } }
        }
        val density = service.resources.displayMetrics.density
        for (box in wanted - entryCovers.keys) {
            val icon = android.widget.ImageView(service).apply {
                setImageResource(R.drawable.ic_door_closed)
                setColorFilter(androidx.core.content.ContextCompat.getColor(service, R.color.block_hint))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val patch = android.widget.FrameLayout(service).apply {
                setBackgroundColor(androidx.core.content.ContextCompat.getColor(service, R.color.block_background))
                contentDescription = description
                // Takes the tap so it never reaches the button underneath.
                isClickable = true
                setOnClickListener { }
                val size = (24 * density).toInt()
                addView(icon, android.widget.FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            }
            val params = WindowManager.LayoutParams(
                box.right - box.left,
                box.bottom - box.top,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = box.left
                y = box.top
                // Absolute coordinates, as for the block itself.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
            }
            runCatching { windowManager.addView(patch, params) }
                .onSuccess { entryCovers[box] = patch }
                .onFailure { Log.e(TAG, "entry cover failed", it) }
        }
    }

    fun hideEntryCovers() {
        entryCovers.values.forEach { runCatching { windowManager.removeView(it) } }
        entryCovers.clear()
    }

    private var cover: CoverView? = null
    private var closeButton: Button? = null
    private var coverBounds: Rect? = null
    private var coverWithButton = false

    val isCoverShowing: Boolean get() = cover != null

    /**
     * Covers an app's floating window, wherever it has been dragged to.
     *
     * The cover itself is not touchable, on purpose: the user must still be able
     * to drag the window away or tap it open -- which lands on the full app,
     * where the normal block takes over. A cover that swallowed touches would
     * trap the window on screen.
     *
     * Getting rid of the window is the part that needs care, because the system
     * draws its close button and the cover hid it. With [closeLabel] and
     * [onClose] a small touchable strip along the bottom says "close video" and
     * does it, and the rest stays solid. Without them -- when closing that way
     * is not working -- the top-right corner, where the system draws its
     * button, is left open instead (see [PipHole]).
     */
    fun showCover(
        bounds: Rect,
        label: String,
        hint: String,
        closeLabel: String? = null,
        onClose: (() -> Unit)? = null,
    ) {
        if (debugSuppress) return
        val withButton = closeLabel != null && onClose != null
        val existing = cover
        if (existing != null && coverWithButton != withButton) hideCover()
        val current = cover
        if (current != null) {
            if (coverBounds != bounds) {
                coverBounds = Rect(bounds)
                runCatching { windowManager.updateViewLayout(current, coverParams(bounds)) }
                closeButton?.let {
                    runCatching { windowManager.updateViewLayout(it, buttonParams(bounds)) }
                }
            }
            return
        }
        val view = CoverView(service, label, hint, withHole = !withButton)
        val added = runCatching { windowManager.addView(view, coverParams(bounds)) }
        if (added.isFailure) {
            Log.e(TAG, "cover failed", added.exceptionOrNull())
            return
        }
        cover = view
        coverBounds = Rect(bounds)
        coverWithButton = withButton
        if (withButton) {
            val button = Button(service).apply {
                text = closeLabel
                isAllCaps = false
                // The block screen's pill, so the two read as one product.
                setBackgroundResource(R.drawable.bg_block_button)
                setTextColor(service.getColor(R.color.block_on_button))
                stateListAnimator = null
                setOnClickListener { onClose?.invoke() }
            }
            runCatching { windowManager.addView(button, buttonParams(bounds)) }
                .onSuccess { closeButton = button }
                .onFailure { Log.e(TAG, "close button failed", it) }
        }
        Log.i(TAG, "cover shown at ${bounds.flattenToString()} button=$withButton")
    }

    fun hideCover() {
        closeButton?.let { runCatching { windowManager.removeView(it) } }
        closeButton = null
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
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = bounds.left
        y = bounds.top
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
        }
    }

    /** The touchable strip: the bottom of the window, as wide as it is. */
    private fun buttonParams(bounds: Rect): WindowManager.LayoutParams {
        val height = (CLOSE_BUTTON_DP * service.resources.displayMetrics.density).toInt()
            .coerceAtMost(bounds.height())
        return WindowManager.LayoutParams(
            bounds.width(),
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Touchable, unlike the cover, but never focusable.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.bottom - height
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }
    }

    /**
     * The cover itself: dark all over, with what it is and, when there is no
     * button, how to close the window. With [withHole] the top-right corner is
     * left open for the system's own close button.
     */
    private class CoverView(
        context: Context,
        private val label: String,
        private val hint: String,
        private val withHole: Boolean,
    ) : View(context) {

        private val density = context.resources.displayMetrics.density
        private val fill = Paint().apply { color = 0xFF0F1A16.toInt() }
        private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFD5E2DB.toInt()
            textSize = 12 * density
        }
        private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8FA39A.toInt()
            textSize = 11 * density
        }

        /** Laid out once per size rather than on every frame. */
        private class Text(val title: StaticLayout, val sub: StaticLayout?, val pad: Int, val y: Int)

        private var hole = PipHole.Box(0, 0, 0, 0)
        private var boxes: List<PipHole.Box> = emptyList()
        private var text: Text? = null

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            hole = if (withHole) PipHole.hole(w, h, density) else PipHole.Box(w, 0, w, 0)
            boxes = PipHole.covered(w, h, hole)
            val pad = (10 * density).toInt()
            val textWidth = w - 2 * pad
            text = if (textWidth <= 0) {
                null
            } else {
                val title = layout(label, labelPaint, textWidth)
                // The hint is only for the version with no button.
                val sub = if (withHole) layout(hint, hintPaint, textWidth) else null
                val gap = (6 * density).toInt()
                val total = title.height + (sub?.let { gap + it.height } ?: 0)
                val top = hole.bottom + pad
                val bottomReserved = if (withHole) 0 else (CLOSE_BUTTON_DP * density).toInt()
                Text(title, sub, pad, top + ((h - top - pad - bottomReserved - total) / 2).coerceAtLeast(0))
            }
        }

        override fun onDraw(canvas: Canvas) {
            boxes.forEach {
                canvas.drawRect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat(), fill)
            }
            val t = text ?: return
            canvas.withTranslation(t.pad.toFloat(), t.y.toFloat()) {
                t.title.draw(this)
                t.sub?.let { sub ->
                    translate(0f, (t.title.height + (6 * density).toInt()).toFloat())
                    sub.draw(this)
                }
            }
        }

        private fun layout(text: String, paint: TextPaint, width: Int) =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build()
    }

    private companion object {
        const val TAG = "Doorman"
        const val CLOSE_BUTTON_DP = 48
    }
}
