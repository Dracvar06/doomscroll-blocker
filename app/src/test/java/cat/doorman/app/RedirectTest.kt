package cat.doorman.app

import cat.doorman.app.data.Redirect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the block's button sends you: how the choice is stored and read back. */
class RedirectTest {

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
