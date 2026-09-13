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
        /** See [cat.doorman.app.rules.ScreenRule.allowSeconds]. */
        val allowMillis: Long? = null,
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
    /**
     * Which known screen this is, whether or not the user holds it.
     *
     * [evaluate] only looks at the screens that are switched on, because it
     * decides what to block. This looks at all of them, because the report
     * needs to say "three hours in Stories" precisely when Stories is the
     * screen somebody chose not to block. Same rules, same order, so the name
     * given here is the one a block would have used.
     */
    fun recognise(
        snapshot: ScreenSnapshot,
        rules: RuleSet,
        arrivedFromAnotherApp: Boolean = false,
    ): String? {
        val app = rules.appFor(snapshot.packageName) ?: return null
        return app.screens.firstOrNull { rule ->
            rule.matchers.any { matches(snapshot, it, arrivedFromAnotherApp) }
        }?.id
    }

    fun evaluate(
        snapshot: ScreenSnapshot,
        rules: RuleSet,
        enabledScreenIds: Set<String>,
        arrivedFromAnotherApp: Boolean = false,
    ): Verdict {
        val app = rules.appFor(snapshot.packageName) ?: return Verdict.ALLOW
        val rule = app.screens.firstOrNull { rule ->
            // A rule with no matcher at all is a switch that modifies another
            // block, not a screen; it must never block anything on its own.
            rule.id in enabledScreenIds &&
                rule.matchers.any { matches(snapshot, it, arrivedFromAnotherApp) }
        } ?: return Verdict.ALLOW
        val outcome =
            if (rule.verdict == "ALLOW_ONCE") Outcome.ALLOW_ONCE else Outcome.BLOCK
        return Verdict(
            outcome = outcome,
            screenId = rule.id,
            labelKey = rule.labelKey,
            budget = rule.budget,
            allowMillis = rule.allowSeconds?.let { it * 1000L },
        )
    }

    fun matches(
        snapshot: ScreenSnapshot,
        matcher: Matcher,
        arrivedFromAnotherApp: Boolean = false,
    ): Boolean {
        matcher.arrivedFromAnotherApp?.let { wanted ->
            if (wanted != arrivedFromAnotherApp) return false
        }
        matcher.selectedViewId?.let { wanted ->
            // Selected *and* on screen. Instagram slides the camera in beside
            // the feed rather than opening it on top, and leaves the feed tab
            // marked selected the whole time it is there -- so a check on
            // selection alone covered the story composer with "the home feed is
            // blocked", which stopped people posting a story because they had
            // asked not to read one.
            if (snapshot.nodes.none { node ->
                    node.selected && node.visible && wanted.any(node::viewIdMatches)
                }
            ) {
                return false
            }
        }
        matcher.visibleViewId?.let { wanted ->
            if (!wanted.all { id ->
                    snapshot.nodes.any { node -> node.visible && node.viewIdMatches(id) }
                }
            ) {
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
