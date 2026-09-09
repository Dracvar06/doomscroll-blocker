package cat.doorman.app

import cat.doorman.app.model.ScreenSnapshot
import cat.doorman.app.model.UiNode
import cat.doorman.app.rules.AppRules
import cat.doorman.app.rules.Matcher
import cat.doorman.app.rules.RuleSet
import cat.doorman.app.rules.ScreenEvaluator
import cat.doorman.app.rules.ScreenRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The report must name a screen the user chose not to block. */
class RecogniseTest {

    private val rules = RuleSet(
        apps = mapOf(
            "com.example.app" to AppRules(
                labelKey = "app",
                screens = listOf(
                    ScreenRule(id = "stories", labelKey = "stories", match = Matcher(anyViewId = listOf("story_viewer"))),
                ),
            ),
        ),
    )
    private val onStories = ScreenSnapshot(
        packageName = "com.example.app",
        nodes = listOf(UiNode(viewId = "story_viewer")),
    )

    @Test
    fun `a screen that is not held is still recognised`() {
        assertEquals("stories", ScreenEvaluator.recognise(onStories, rules))
        assertEquals(
            ScreenEvaluator.Outcome.ALLOW,
            ScreenEvaluator.evaluate(onStories, rules, enabledScreenIds = emptySet()).outcome,
        )
    }

    @Test
    fun `nothing matching is nobody's screen, not somebody's`() {
        val elsewhere = onStories.copy(nodes = listOf(UiNode(viewId = "inbox")))
        assertNull(ScreenEvaluator.recognise(elsewhere, rules))
    }
}
