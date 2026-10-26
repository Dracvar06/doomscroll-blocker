package cat.doorman.app

import cat.doorman.app.model.ScreenSnapshot
import cat.doorman.app.rules.EntryButtons
import cat.doorman.app.rules.EntryButtons.Box
import cat.doorman.app.rules.RuleSet
import cat.doorman.app.rules.ScreenRule
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The buttons into blocked screens, found on real screens -- and, the half
 * that matters, never a button somebody needs. Covering the messages tab by
 * mistake would be the same failure as blocking the inbox.
 */
class EntryButtonsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): ScreenSnapshot {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/$name.json")
            ?: error("missing fixture $name")
        return json.decodeFromString(stream.bufferedReader().readText())
    }

    private val rules: RuleSet =
        json.decodeFromString(java.io.File("src/main/assets/rules.json").readText())

    private fun rule(id: String): ScreenRule =
        rules.apps.values.flatMap { it.screens }.first { it.id == id }

    private fun boundsOf(fixture: String, viewId: String): Box {
        val n = fixture(fixture).nodes.first { it.visible && it.viewIdMatches(viewId) }
        val (l, t, r, b) = n.bounds!!.split(',').map { it.toInt() }
        return Box(l, t, r, b)
    }

    @Test
    fun `the reels button is found on instagram's home, and only it`() {
        val found = EntryButtons.find(fixture("instagram-feed"), rule("ig_reels"))
        assertEquals(listOf(boundsOf("instagram-feed", "clips_tab")), found)
        assertTrue(boundsOf("instagram-feed", "direct_tab") !in found)
    }

    @Test
    fun `and on the profile`() {
        assertEquals(1, EntryButtons.find(fixture("instagram-profile"), rule("ig_reels")).size)
    }

    @Test
    fun `a conversation, with no tab bar on screen, has nothing to cover`() {
        assertEquals(emptyList<Box>(), EntryButtons.find(fixture("instagram-dm-thread"), rule("ig_reels")))
    }

    /**
     * YouTube names none of its tabs. Shorts is the second; the first is
     * Home and the fourth Subscriptions, and covering either would be wrong.
     */
    @Test
    fun `the shorts button is youtube's second tab`() {
        assertEquals(listOf(Box(216, 2235, 432, 2361)), EntryButtons.find(fixture("youtube-home"), rule("yt_shorts")))
        assertEquals(listOf(Box(216, 2235, 432, 2361)), EntryButtons.find(fixture("youtube-subs"), rule("yt_shorts")))
    }

    @Test
    fun `the spotlight button on snapchat`() {
        assertEquals(
            listOf(boundsOf("snapchat-chat", "ngs_spotlight_icon_container")),
            EntryButtons.find(fixture("snapchat-chat"), rule("sc_spotlight")),
        )
    }

    @Test
    fun `the market button on rednote`() {
        assertEquals(
            listOf(boundsOf("rednote-explore", "index_store")),
            EntryButtons.find(fixture("rednote-explore"), rule("rn_market")),
        )
    }

    @Test
    fun `no entry button is ever a way to messages`() {
        val messages = mapOf(
            "instagram-feed" to "direct_tab",
            "snapchat-chat" to "ngs_chat_icon_container",
            "rednote-explore" to "index_message",
        )
        val withEntries = rules.apps.values.flatMap { it.screens }
            .filter { it.entryViewIds.isNotEmpty() || it.entryTab != null }
        for ((screen, inbox) in messages) {
            val inboxBox = boundsOf(screen, inbox)
            for (r in withEntries) {
                assertTrue("${r.id} on $screen", inboxBox !in EntryButtons.find(fixture(screen), r))
            }
        }
    }
}
