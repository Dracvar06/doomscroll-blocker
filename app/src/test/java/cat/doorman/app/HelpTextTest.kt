package cat.doorman.app

import cat.doorman.app.rules.RuleSet
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every block has to be able to say what it covers.
 *
 * "Reels" and "keep swiping from a reel someone sent" are different things,
 * and someone who cannot tell which is which will either block more than they
 * meant and resent the app, or block less and think it is broken. Both end
 * with Doorman switched off, so the explanation is not optional decoration and
 * a screen added without one should fail the build rather than ship mute.
 */
class HelpTextTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun rules(): RuleSet =
        json.decodeFromString(java.io.File("src/main/assets/rules.json").readText())

    private fun strings(file: String): String = java.io.File(file).readText()

    @Test
    fun `every screen names an explanation`() {
        val missing = rules().apps.values.flatMap { it.screens }
            .filter { it.helpKey.isNullOrBlank() }
            .map { it.id }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `every explanation a screen names actually exists`() {
        val english = strings("src/main/res/values/strings.xml")
        val missing = rules().apps.values.flatMap { it.screens }
            .mapNotNull { it.helpKey }
            .filterNot { english.contains("name=\"$it\"") }
        assertEquals(emptyList<String>(), missing)
    }

    /**
     * The app ships in three languages and an untranslated explanation is a
     * paragraph of English in the middle of a Catalan screen -- worse than the
     * two-word label it was meant to clarify.
     */
    @Test
    fun `explanations are translated into every language`() {
        val keys = rules().apps.values.flatMap { it.screens }.mapNotNull { it.helpKey }
        listOf("values-ca", "values-es").forEach { dir ->
            val text = strings("src/main/res/$dir/strings.xml")
            val missing = keys.filterNot { text.contains("name=\"$it\"") }
            assertEquals("missing from $dir", emptyList<String>(), missing)
        }
    }

    @Test
    fun `screen names are translated into every language too`() {
        val keys = rules().apps.values.flatMap { it.screens }.map { it.labelKey } +
            rules().apps.values.map { it.labelKey }
        listOf("values-ca", "values-es").forEach { dir ->
            val text = strings("src/main/res/$dir/strings.xml")
            val missing = keys.filterNot { text.contains("name=\"$it\"") }
            assertEquals("missing from $dir", emptyList<String>(), missing)
        }
    }

    @Test
    fun `no explanation is left as a placeholder`() {
        val english = strings("src/main/res/values/strings.xml")
        assertTrue(
            Regex("name=\"help_[a-z_]+\"[^>]*>\\s*(TODO|TBD)").find(english) == null,
        )
    }
}
