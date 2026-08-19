package cat.doorman.app.rules

import cat.doorman.app.model.ScreenSnapshot

/**
 * Decides whether the screen in front of the user should be blocked.
 *
 * Pure: no Android types, no I/O, no clock. That is what lets the trickiest
 * logic in the app be tested against real captured screens.
 */
object ScreenEvaluator {

    enum class Outcome {
        /** Nothing to do. */
        ALLOW,

        /** Cover it. */
        BLOCK,

        /**
         * Watchable, but only for a while: the caller tracks a budget and blocks
         * once it is spent. A reel a friend sent you should play; the twentieth
         * reel after it should not.
         */
        ALLOW_ONCE,
    }

    data class Verdict(
        val outcome: Outcome,
        val screenId: String? = null,
        val labelKey: String? = null,
        val budget: Int = 1,
    ) {
        val blocked: Boolean get() = outcome == Outcome.BLOCK

        companion object {
            val ALLOW = Verdict(Outcome.ALLOW)
        }
    }

    /**
     * A screen is blocked only when a rule matches *and* the user has switched
     * that screen on. Anything unrecognised is allowed: wrongly blocking a
     * screen someone needs teaches them to bypass the app, which is a far worse
     * failure than missing a feed.
     */
    fun evaluate(
        snapshot: ScreenSnapshot,
        rules: RuleSet,
        enabledScreenIds: Set<String>,
    ): Verdict {
        val app = rules.apps[snapshot.packageName] ?: return Verdict.ALLOW
        val rule = app.screens.firstOrNull {
            it.id in enabledScreenIds && matches(snapshot, it.matcher())
        } ?: return Verdict.ALLOW
        val outcome =
            if (rule.verdict == "ALLOW_ONCE") Outcome.ALLOW_ONCE else Outcome.BLOCK
        return Verdict(
            outcome = outcome,
            screenId = rule.id,
            labelKey = rule.labelKey,
            budget = rule.budget,
        )
    }

    private fun ScreenRule.matcher(): Matcher = match

    fun matches(snapshot: ScreenSnapshot, matcher: Matcher): Boolean {
        matcher.selectedViewId?.let { wanted ->
            if (snapshot.nodes.none { node -> node.selected && wanted.any(node::viewIdMatches) }) {
                return false
            }
        }
        matcher.anyViewId?.let { wanted ->
            if (snapshot.nodes.none { node -> wanted.any(node::viewIdMatches) }) return false
        }
        matcher.allViewId?.let { wanted ->
            if (!wanted.all { id -> snapshot.nodes.any { it.viewIdMatches(id) } }) return false
        }
        matcher.noneViewId?.let { unwanted ->
            if (snapshot.nodes.any { node -> unwanted.any(node::viewIdMatches) }) return false
        }
        matcher.selectedTab?.let { tab ->
            val container = snapshot.findByViewId(tab.under) ?: return false
            // A tab strip marks the chosen tab selected, and marks a label
            // inside it selected too, so take the shallowest one -- that is the
            // tab itself rather than its caption.
            val selected = snapshot.descendantsOf(container)
                .filter { it.selected }
                .minByOrNull { it.depth } ?: return false
            if (selected.index != tab.index) return false
        }
        return true
    }
}
