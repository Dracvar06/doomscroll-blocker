package cat.doorman.app.debug

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the live accessibility tree to a JSON file so screen fingerprints can
 * be derived from the app versions actually installed, rather than from
 * published view-ids that may be several releases stale.
 *
 * PRIVACY: a dump records on-screen text, which on a DM screen means the
 * contents of messages. Files stay in the app's own external directory and are
 * never uploaded. Read one before sharing it in a bug report.
 */
object TreeDumper {

    private const val MAX_DEPTH = 25
    private const val MAX_NODES = 1200

    @Serializable
    data class Node(
        val depth: Int,
        /** Index within the parent. Position is stable across languages; the
         *  content descriptions beside it are not. */
        val index: Int = 0,
        val className: String? = null,
        val viewId: String? = null,
        val contentDescription: String? = null,
        val text: String? = null,
        val bounds: String? = null,
        val scrollable: Boolean = false,
        val clickable: Boolean = false,
        val selected: Boolean = false,
        val focused: Boolean = false,
        val editable: Boolean = false,
        val visible: Boolean = false,
        val childCount: Int = 0,
    )

    @Serializable
    data class Dump(
        val warning: String =
            "May contain on-screen text, including private messages. Review before sharing.",
        val capturedAt: String,
        val packageName: String? = null,
        val rootClassName: String? = null,
        val truncated: Boolean = false,
        val nodeCount: Int = 0,
        val nodes: List<Node> = emptyList(),
    )

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /**
     * Picks the window to read.
     *
     * rootInActiveWindow is not good enough: it follows input focus, so a system
     * overlay -- the status bar, a gesture hint, the volume panel -- can be
     * "active" while the app the user is actually looking at sits behind it.
     * Dumping that yields 50 nodes of systemui and no sign of the feed. The same
     * mistake in the blocking path would mean evaluating the wrong screen, so
     * application windows are selected explicitly, active ones first.
     */
    private fun applicationWindowRoot(service: AccessibilityService): AccessibilityNodeInfo? {
        val windows = service.windows ?: return null
        return windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { (if (it.isActive) 2 else 0) + (if (it.isFocused) 1 else 0) }
            .mapNotNull { it.root }
            .firstOrNull()
    }

    /**
     * Returns the file written, or null if there was no window to read.
     *
     * [redact] replaces every piece of on-screen text and every content
     * description with a placeholder. It defaults to true because the common
     * case is a user sending this to a stranger on the internet to report a
     * broken rule, and on a conversation screen the raw text is their messages.
     * The rules only ever match on view ids and structure, so a redacted report
     * is exactly as useful as an unredacted one.
     */
    fun dump(service: AccessibilityService, label: String?, redact: Boolean = true): File? {
        val root = applicationWindowRoot(service) ?: service.rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "dump: no window to read")
            return null
        }
        service.windows?.forEach {
            Log.i(TAG, "  window type=${it.type} active=${it.isActive} focused=${it.isFocused} pkg=${it.root?.packageName}")
        }

        val nodes = mutableListOf<Node>()
        var truncated = false

        fun walk(node: AccessibilityNodeInfo?, depth: Int, index: Int) {
            if (node == null) return
            if (depth > MAX_DEPTH || nodes.size >= MAX_NODES) {
                truncated = true
                return
            }
            val rect = Rect().also { node.getBoundsInScreen(it) }
            nodes += Node(
                depth = depth,
                index = index,
                className = node.className?.toString(),
                viewId = node.viewIdResourceName,
                contentDescription = node.contentDescription?.toString()
                    ?.let { if (redact) REDACTED else it },
                text = node.text?.toString()?.let { if (redact) REDACTED else it },
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

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dump = Dump(
            warning = if (redact) {
                "Screen structure only. All text and content descriptions removed."
            } else {
                "May contain on-screen text, including private messages. Review before sharing."
            },
            capturedAt = stamp,
            packageName = root.packageName?.toString(),
            rootClassName = root.className?.toString(),
            truncated = truncated,
            nodeCount = nodes.size,
            nodes = nodes,
        )

        // The app's *external* files dir, so `adb pull` can reach it without
        // run-as. Still app-scoped: no other app can read it.
        val dir = File(service.getExternalFilesDir(null), "dumps").apply { mkdirs() }
        val name = buildString {
            append(stamp)
            append('-')
            append(root.packageName?.toString()?.substringAfterLast('.') ?: "unknown")
            if (!label.isNullOrBlank()) append('-').append(label.replace(Regex("[^A-Za-z0-9_-]"), ""))
            append(".json")
        }
        val file = File(dir, name)
        file.writeText(json.encodeToString(dump))

        Log.i(TAG, "dump: ${dump.packageName} -> ${file.absolutePath} (${nodes.size} nodes, truncated=$truncated)")
        // A skim of what makes this screen identifiable, so the log alone is
        // often enough to spot the fingerprint.
        nodes.asSequence()
            .filter { it.viewId != null && it.visible }
            .map { it.viewId!!.substringAfter("id/") }
            .distinct()
            .take(40)
            .forEach { Log.i(TAG, "  viewId: $it") }
        return file
    }

    private const val REDACTED = "<redacted>"
    private const val TAG = "Doorman"
}
