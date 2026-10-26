package cat.doorman.app.rules

/**
 * Which screens a tapped tab leads to: those recognised by that tab being the
 * selected one, as Instagram's Home, Reels and Search are.
 *
 * Tapping one starts the switch at once, but the new screen is only judged a
 * few hundred milliseconds later, once the app has gone quiet; for that long
 * the feed was in view. Knowing where the tap leads lets the screen be covered
 * at the tap.
 *
 * Pure, so which tab counts has tests instead of a phone.
 */
object TabTargets {

    fun screensFor(app: AppRules, viewId: String): List<String> {
        val id = viewId.substringAfter("id/")
        return app.screens
            .filter { screen -> screen.matchers.any { id in it.selectedViewId.orEmpty() } }
            .map { it.id }
    }
}
