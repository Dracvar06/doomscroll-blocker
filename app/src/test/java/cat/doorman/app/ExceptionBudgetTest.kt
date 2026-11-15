package cat.doorman.app

import cat.doorman.app.limits.Allowance
import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.LimitsCodec
import cat.doorman.app.limits.Period
import cat.doorman.app.limits.isLoosening
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An app's limit over the whole app except some screens: every minute in
 * Instagram counts but the ones in a conversation. Counting reaches screens
 * nobody recognised, so time cannot leak out through them; covering does not,
 * because an unrecognised screen might be a conversation.
 */
class ExceptionBudgetTest {

    private val minutes = Allowance(45, Period.DAY)
    private val messages = setOf("ig_dm_inbox", "ig_dm_thread")
    private val allButMessages = Limits(allowances = listOf(minutes), exceptOn = messages)

    @Test
    fun `counts every screen but the excepted ones, unknown screens included`() {
        assertTrue(allButMessages.appliesOn("ig_feed"))
        assertTrue(allButMessages.appliesOn("ig_reels"))
        assertTrue(allButMessages.appliesOn(null))
        assertFalse(allButMessages.appliesOn("ig_dm_thread"))
        assertFalse(allButMessages.appliesOn("ig_dm_inbox"))
    }

    @Test
    fun `covers recognised screens only, never an excepted or unknown one`() {
        assertTrue(allButMessages.coversOn("ig_feed"))
        assertFalse(allButMessages.coversOn(null))
        assertFalse(allButMessages.coversOn("ig_dm_thread"))
    }

    @Test
    fun `a whole-app limit with no exceptions covers unknown screens too`() {
        val wholeApp = Limits(allowances = listOf(minutes))
        assertTrue(wholeApp.coversOn(null))
        assertTrue(wholeApp.coversOn("yt_watch"))
    }

    @Test
    fun `a freed part joins the exceptions`() {
        val storiesFree = allButMessages.copy(exceptOn = messages + "ig_stories")
        assertFalse(storiesFree.appliesOn("ig_stories"))
        assertTrue(storiesFree.appliesOn("ig_reels"))
    }

    @Test
    fun `the exceptions survive a round trip`() {
        assertEquals(allButMessages, LimitsCodec.decode(LimitsCodec.encode(allButMessages)))
    }

    @Test
    fun `stored limits from before exceptions existed decode with none`() {
        val old = LimitsCodec.encode(Limits(allowances = listOf(minutes)))
        assertNull(LimitsCodec.decode(old)!!.exceptOn)
    }

    @Test
    fun `damaged exceptions keep the limit off every screen, messages first`() {
        val damaged = LimitsCodec.encode(allButMessages).replace(Regex("\"exceptOn\":\\[[^]]*]"), "\"exceptOn\":42")
        val limits = LimitsCodec.decode(damaged)!!
        assertEquals(listOf(minutes), limits.allowances)
        assertFalse(limits.appliesOn("ig_dm_thread"))
        assertFalse(limits.appliesOn("ig_feed"))
        assertFalse(limits.appliesOn(null))
    }

    @Test
    fun `freeing a screen is loosening and counting it again is not`() {
        assertTrue(isLoosening(allButMessages, allButMessages.copy(exceptOn = messages + "ig_stories")))
        assertFalse(isLoosening(allButMessages.copy(exceptOn = messages + "ig_stories"), allButMessages))
    }

    @Test
    fun `adding exceptions to a whole-app limit is loosening, dropping them is not`() {
        val wholeApp = Limits(allowances = listOf(minutes))
        assertTrue(isLoosening(wholeApp, allButMessages))
        assertFalse(isLoosening(allButMessages, wholeApp))
    }
}
