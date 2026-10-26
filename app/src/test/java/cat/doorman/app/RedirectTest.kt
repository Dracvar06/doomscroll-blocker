package cat.doorman.app

import cat.doorman.app.data.Redirect
import cat.doorman.app.service.RedirectPolicy
import cat.doorman.app.service.RedirectPolicy.DWELL_MS
import cat.doorman.app.service.RedirectPolicy.QUIET_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Being sent somewhere better when a block holds you -- and, the half that
 * matters, not when you are only passing through one.
 */
class RedirectTest {

    // --------------------------------------------------------------- timing

    @Test
    fun `passing through a block on the way to your messages sends you nowhere`() {
        // Instagram opens on its feed; the messages are a tap away. Gone in two
        // seconds, the block was never holding anyone.
        assertFalse(RedirectPolicy.due(shownAt = 0, lastActivityAt = 0, lastRedirectAt = null, now = 2_000, interactive = true))
    }

    @Test
    fun `staying on a block sends you on`() {
        assertTrue(RedirectPolicy.due(0, 0, null, DWELL_MS, true))
    }

    @Test
    fun `using what the block leaves open starts the wait again`() {
        // Scrolling the stories row above a blocked feed at 4 s.
        assertFalse(RedirectPolicy.due(0, 4_000, null, DWELL_MS, true))
        assertTrue(RedirectPolicy.due(0, 4_000, null, 4_000 + DWELL_MS, true))
    }

    @Test
    fun `a screen that is off sends nobody anywhere`() {
        assertFalse(RedirectPolicy.due(0, 0, null, 60_000, false))
    }

    @Test
    fun `coming back on purpose is not bounced straight out again`() {
        val sentAt = 10_000L
        // Back at 20 s, through the feed, on the way to a message.
        assertFalse(RedirectPolicy.due(20_000, 20_000, sentAt, 20_000 + DWELL_MS, true))
        assertTrue(RedirectPolicy.due(sentAt + QUIET_MS, sentAt + QUIET_MS, sentAt, sentAt + QUIET_MS + DWELL_MS, true))
    }

    @Test
    fun `the next look is when both the wait and the quiet are over`() {
        assertEquals(DWELL_MS, RedirectPolicy.nextCheckAt(0, 0, null))
        assertEquals(3_000 + DWELL_MS, RedirectPolicy.nextCheckAt(0, 3_000, null))
        assertEquals(1_000 + QUIET_MS, RedirectPolicy.nextCheckAt(2_000, 2_000, 1_000))
    }

    // ---------------------------------------------------------------- where

    @Test
    fun `a choice survives being stored`() {
        for (r in listOf(
            Redirect.App("com.google.android.apps.books"),
            Redirect.WhatsAppChat("34612345678", "Àvia"),
            Redirect.WhatsAppChat("34612345678", "Name | with a bar"),
        )) assertEquals(r, Redirect.decode(Redirect.encode(r)))
        assertNull(Redirect.encode(null))
    }

    @Test
    fun `a stored value that makes no sense is nowhere, not a crash`() {
        for (raw in listOf(null, "", "nonsense", "app:", "wa:12|x", "wa:|Àvia")) {
            assertNull(raw, Redirect.decode(raw))
        }
    }

    @Test
    fun `phone numbers are read the way people type them`() {
        assertEquals("34612345678", Redirect.normaliseNumber("+34 612 34 56 78"))
        assertEquals("34612345678", Redirect.normaliseNumber("0034-612-345-678"))
        assertEquals("18055551234", Redirect.normaliseNumber("+1 (805) 555-1234"))
    }

    @Test
    fun `a number without its country code is refused rather than chatting with nobody`() {
        assertNull(Redirect.normaliseNumber("612 34"))
        assertNull(Redirect.normaliseNumber("0612345678"))
        assertNull(Redirect.normaliseNumber("+34 612 abc"))
        assertNull(Redirect.normaliseNumber("1234567890123456"))
    }
}
