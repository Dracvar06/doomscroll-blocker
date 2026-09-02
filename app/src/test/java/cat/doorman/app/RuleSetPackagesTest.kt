package cat.doorman.app

import cat.doorman.app.rules.RuleSet
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which packages Doorman acts on is derived from rules.json, not listed a
 * second time in code. These tests guard that, because the failure mode of a
 * forgotten second list is an app that blocks nothing and says nothing.
 */
class RuleSetPackagesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun rules(): RuleSet =
        json.decodeFromString(java.io.File("src/main/assets/rules.json").readText())

    private fun withAliases(): RuleSet = json.decodeFromString(
        """
        {
          "version": 1,
          "apps": {
            "com.zhiliaoapp.musically": {
              "labelKey": "app_tiktok",
              "aliases": ["com.ss.android.ugc.trill"],
              "screens": []
            }
          }
        }
        """.trimIndent()
    )

    @Test
    fun `every app in the rules is a package the service acts on`() {
        val rules = rules()
        assertTrue(rules.apps.keys.all { it in rules.supportedPackages })
    }

    @Test
    fun `an alias is supported just like the name it stands in for`() {
        val rules = withAliases()
        assertTrue("com.zhiliaoapp.musically" in rules.supportedPackages)
        assertTrue("com.ss.android.ugc.trill" in rules.supportedPackages)
    }

    @Test
    fun `an alias resolves to the same rules as the main package`() {
        val rules = withAliases()
        assertSame(
            rules.appFor("com.zhiliaoapp.musically"),
            rules.appFor("com.ss.android.ugc.trill"),
        )
    }

    /**
     * A pass is granted against the name in rules.json and spent against the
     * name the phone reports. On a regional build those differ, and comparing
     * them directly would hand someone a pass that quietly does nothing.
     */
    @Test
    fun `a pass granted for an app is honoured under its alias`() {
        val rules = withAliases()
        assertEquals(
            rules.canonicalPackage("com.zhiliaoapp.musically"),
            rules.canonicalPackage("com.ss.android.ugc.trill"),
        )
    }

    @Test
    fun `an app nobody wrote rules for is left alone`() {
        val rules = rules()
        assertTrue("com.whatsapp" !in rules.supportedPackages)
        assertNull(rules.appFor("com.whatsapp"))
        assertNull(rules.canonicalPackage("com.whatsapp"))
    }

    @Test
    fun `an unknown package resolves to nothing rather than the first app`() {
        assertNull(withAliases().appFor("com.example.other"))
    }
}
