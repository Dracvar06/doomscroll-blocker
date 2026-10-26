package cat.doorman.app.rules

import cat.doorman.app.model.ScreenSnapshot

/**
 * Where, on the screen in front, the buttons into blocked screens are.
 *
 * Only buttons actually on screen count -- Instagram keeps its tab bar in the
 * tree while a conversation is open over it -- and only ones with a real size,
 * because a cover drawn over a zero-height button would be drawn over whatever
 * is next to it instead.
 *
 * Pure, so which button gets covered has tests instead of a phone. Covering
 * the wrong one is the same failure as blocking the wrong screen.
 */
object EntryButtons {

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun find(snapshot: ScreenSnapshot, rule: ScreenRule): List<Box> {
        val byId = rule.entryViewIds.flatMap { id ->
            snapshot.nodes.filter { it.visible && it.viewIdMatches(id) }
        }
        val byPosition = rule.entryTab?.let { tab ->
            val bar = snapshot.findByViewId(tab.under)?.takeIf { it.visible }
            bar?.let { container ->
                // The tabs are the first level of the bar that holds several
                // of anything: YouTube wraps its row of tabs in one layout,
                // and a tab's caption sits a level below the tab.
                val tabs = snapshot.descendantsOf(container)
                    .groupBy { it.depth }
                    .toSortedMap()
                    .values
                    .firstOrNull { level -> level.size >= 2 }
                tabs?.firstOrNull { it.index == tab.index && it.visible }
            }
        }
        return (byId + listOfNotNull(byPosition)).mapNotNull(::boxOf).distinct()
    }

    private fun boxOf(node: cat.doorman.app.model.UiNode): Box? {
        val parts = node.bounds?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return null
        if (parts.size != 4) return null
        val (l, t, r, b) = parts
        return if (r > l && b > t) Box(l, t, r, b) else null
    }
}
