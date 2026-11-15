package cat.doorman.app

import cat.doorman.app.model.ScreenSnapshot
import cat.doorman.app.rules.RuleSet
import cat.doorman.app.rules.ScreenEvaluator
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The message screens of Instagram and Snapchat are recognised, never held.
 *
 * Doorman counts an app's time except in messages, so it must know where they
 * are. Missing one is the worst failure (a conversation could be blocked);
 * recognising a feed as messages is only a leak. Both directions are tested
 * against captured screens, redacted down to view ids and structure.
 */
class MessageScreensTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): ScreenSnapshot {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/$name.json")
            ?: error("missing fixture $name")
        return json.decodeFromString(stream.bufferedReader().readText())
    }

    private fun rules(): RuleSet =
        json.decodeFromString(java.io.File("src/main/assets/rules.json").readText())

    private val messageIds = setOf("ig_dm_inbox", "ig_dm_thread", "sc_chat_list", "sc_chat_thread")

    private val expected = mapOf(
        "instagram-dm-inbox" to "ig_dm_inbox",
        "instagram-dm-requests" to "ig_dm_inbox",
        "instagram-dm-thread" to "ig_dm_thread",
        "instagram-dm-thread-closed-keyboard" to "ig_dm_thread",
        "snapchat-chat" to "sc_chat_list",
        "snapchat-chat-list" to "sc_chat_list",
        "snapchat-chat-thread" to "sc_chat_thread",
        "snapchat-chat-thread-keyboard" to "sc_chat_thread",
    )

    private val notMessages = listOf(
        "instagram-clips", "instagram-explore-idle", "instagram-feed-cold", "instagram-feed",
        "instagram-home-with-stories", "instagram-profile", "instagram-reel-comments",
        "instagram-reel-from-feed", "instagram-search-active", "instagram-search",
        "instagram-shared-reel", "instagram-single-post", "instagram-story-composer",
        "instagram-story-viewer", "instagram-notifications",
        "snapchat-camera", "snapchat-map", "snapchat-memories", "snapchat-spotlight",
        "snapchat-stories",
    )

    @Test
    fun `every captured message screen is recognised as its own id`() {
        for ((name, id) in expected) {
            assertEquals(name, id, ScreenEvaluator.recognise(fixture(name), rules()))
        }
    }

    @Test
    fun `no other instagram or snapchat screen is recognised as messages`() {
        for (name in notMessages) {
            val got = ScreenEvaluator.recognise(fixture(name), rules())
            assertFalse("$name was taken for $got", got in messageIds)
        }
    }

    @Test
    fun `the keyboard makes no difference to a snapchat conversation`() {
        assertEquals(
            ScreenEvaluator.recognise(fixture("snapchat-chat-thread"), rules()),
            ScreenEvaluator.recognise(fixture("snapchat-chat-thread-keyboard"), rules()),
        )
    }

    @Test
    fun `message rules are flagged, off by default and never block`() {
        val screens = rules().apps.values.flatMap { it.screens }.filter { it.id in messageIds }
        assertEquals(messageIds, screens.map { it.id }.toSet())
        for (s in screens) {
            assertTrue(s.id, s.messages)
            assertFalse(s.id, s.defaultEnabled)
        }
        // Even if something switches them all on, a conversation stays open.
        for ((name, _) in expected) {
            val verdict = ScreenEvaluator.evaluate(fixture(name), rules(), messageIds)
            assertFalse(name, verdict.blocked)
            assertEquals(name, ScreenEvaluator.Outcome.ALLOW, verdict.outcome)
        }
    }

    @Test
    fun `only the message rules carry the flag`() {
        val flagged = rules().apps.values.flatMap { it.screens }.filter { it.messages }
            .map { it.id }.toSet()
        assertEquals(messageIds, flagged)
    }

    @Test
    fun `the flag is parsed, and absent means false`() {
        val set: RuleSet = json.decodeFromString(
            """{"version":1,"apps":{"a.b":{"labelKey":"x","screens":[
              {"id":"m","labelKey":"l","messages":true,"match":{"visibleViewId":["v"]}},
              {"id":"n","labelKey":"l","match":{"visibleViewId":["v"]}}]}}}""",
        )
        val byId = set.apps.getValue("a.b").screens.associateBy { it.id }
        assertTrue(byId.getValue("m").messages)
        assertFalse(byId.getValue("n").messages)
        assertNotNull(byId["m"])
    }
}
