package cat.doorman.app.model

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Reads the live accessibility tree into a [ScreenSnapshot].
 *
 * Shared by the blocking path and the debug dumper on purpose: a fixture is
 * then a byte-for-byte record of what the evaluator really sees.
 */
object SnapshotCapture {

    const val MAX_DEPTH = 25
    const val MAX_NODES = 1200

    /**
     * Picks the window a blocking decision should be made about.
     *
     * rootInActiveWindow follows input focus, so a system overlay -- status bar,
     * gesture hint, volume panel -- can be "active" while the app the user is
     * looking at sits behind it.
     *
     * Filtering to application windows is still not enough on its own. During
     * the animation of an app opening, systemui owns an application window that
     * is both active and focused, so a decision made in that instant is made
     * about 44 nodes of system UI rather than about Instagram. When the caller
     * knows which package is coming to the front, that package wins.
     */
    fun applicationWindowRoot(
        service: AccessibilityService,
        preferPackage: String? = null,
    ): AccessibilityNodeInfo? {
        val windows = service.windows ?: return service.rootInActiveWindow
        val applicationWindows = windows.filter {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        }
        if (preferPackage != null) {
            applicationWindows.asSequence()
                .mapNotNull { it.root }
                .firstOrNull { it.packageName?.toString() == preferPackage }
                ?.let { return it }
        }
        return applicationWindows.asSequence()
            .sortedByDescending { (if (it.isActive) 2 else 0) + (if (it.isFocused) 1 else 0) }
            .mapNotNull { it.root }
            .firstOrNull() ?: service.rootInActiveWindow
    }

    fun capture(service: AccessibilityService, preferPackage: String? = null): ScreenSnapshot? {
        val root = applicationWindowRoot(service, preferPackage) ?: return null
        return capture(root)
    }

    fun capture(root: AccessibilityNodeInfo): ScreenSnapshot {
        val nodes = mutableListOf<UiNode>()

        fun walk(node: AccessibilityNodeInfo?, depth: Int, index: Int) {
            if (node == null || depth > MAX_DEPTH || nodes.size >= MAX_NODES) return
            val rect = Rect().also { node.getBoundsInScreen(it) }
            nodes += UiNode(
                depth = depth,
                index = index,
                className = node.className?.toString(),
                viewId = node.viewIdResourceName,
                contentDescription = node.contentDescription?.toString(),
                text = node.text?.toString(),
                bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}",
                scrollable = node.isScrollable,
                clickable = node.isClickable,
                selected = node.isSelected,
                focused = node.isFocused,
                editable = node.isEditable,
                visible = node.isVisibleToUser,
                childCount = node.childCount,
            )
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1, i)
        }
        walk(root, 0, 0)

        return ScreenSnapshot(
            packageName = root.packageName?.toString(),
            rootClassName = root.className?.toString(),
            nodes = nodes,
        )
    }
}
