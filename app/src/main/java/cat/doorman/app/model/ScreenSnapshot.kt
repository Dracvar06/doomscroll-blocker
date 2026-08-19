package cat.doorman.app.model

import kotlinx.serialization.Serializable

/**
 * A flattened accessibility tree, in depth-first order.
 *
 * This is deliberately the same type the debug dumper writes to disk, so a
 * captured dump can be replayed straight into the evaluator as a test fixture.
 * The rules that decide whether to block a screen are then tested against real
 * captures from real app versions rather than against hand-written mocks.
 */
@Serializable
data class ScreenSnapshot(
    val packageName: String? = null,
    val rootClassName: String? = null,
    val nodes: List<UiNode> = emptyList(),
) {
    /** Descendants of [node] in depth-first order: everything after it, until depth returns. */
    fun descendantsOf(node: UiNode): List<UiNode> {
        val start = nodes.indexOf(node)
        if (start < 0) return emptyList()
        return nodes.asSequence()
            .drop(start + 1)
            .takeWhile { it.depth > node.depth }
            .toList()
    }

    fun findByViewId(suffix: String): UiNode? = nodes.firstOrNull { it.viewIdMatches(suffix) }
}

@Serializable
data class UiNode(
    val depth: Int = 0,
    /** Index within the parent. Position survives translation; labels do not. */
    val index: Int = 0,
    val className: String? = null,
    val viewId: String? = null,
    val contentDescription: String? = null,
    val text: String? = null,
    val bounds: String? = null,
    val scrollable: Boolean = false,
    val clickable: Boolean = false,
    val selected: Boolean = false,
    /**
     * Whether this node holds input focus. It is what separates a search field
     * sitting idle above a grid of recommendations from one the user is
     * actually typing into.
     */
    val focused: Boolean = false,
    val editable: Boolean = false,
    val visible: Boolean = false,
    val childCount: Int = 0,
) {
    /** View ids arrive fully qualified ("com.foo:id/bar"); rules name only the suffix. */
    fun viewIdMatches(suffix: String): Boolean =
        viewId != null && viewId.substringAfter("id/") == suffix

    /** Top edge in screen pixels, or null if bounds were not recorded. */
    fun topPx(): Int? = bounds?.split(',')?.getOrNull(1)?.toIntOrNull()

    /** Bottom edge in screen pixels, or null if bounds were not recorded. */
    fun bottomPx(): Int? = bounds?.split(',')?.getOrNull(3)?.toIntOrNull()
}
