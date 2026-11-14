package cat.doorman.app

import cat.doorman.app.gray.GrayCodec
import cat.doorman.app.gray.GrayPolicy
import cat.doorman.app.gray.GraySetting
import cat.doorman.app.limits.Days
import cat.doorman.app.limits.Window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * What goes grey and when. The case that shaped it is Instagram: grey on the
 * reels, in colour in the conversation they were sent from.
 */
class GrayscaleTest {

    private fun at(day: DayOfWeek, hour: Int, minute: Int = 0): LocalDateTime {
        // 2026-09-07 is a Monday.
        return LocalDateTime.of(2026, 9, 7, hour, minute).plusDays((day.value - 1).toLong())
    }

    private val noon = at(DayOfWeek.WEDNESDAY, 12)
    private val always = GraySetting()
    private val evenings = GraySetting(listOf(Window(20 * 60, 23 * 60)))

    // ---- which screen ------------------------------------------------------

    @Test
    fun `the reels go grey and the conversation does not`() {
        val settings = mapOf("ig_reels" to always, "ig_single_reel" to always)
        assertTrue(GrayPolicy.shouldBeGray(settings, "com.instagram.android", "ig_reels", noon))
        // A reel somebody sent you is a screen of its own, and it is grey.
        assertTrue(GrayPolicy.shouldBeGray(settings, "com.instagram.android", "ig_single_reel", noon))
        // The conversation is a screen the rules do not name: in colour.
        assertFalse(GrayPolicy.shouldBeGray(settings, "com.instagram.android", null, noon))
    }

    @Test
    fun `a screen with no setting stays in colour`() {
        val settings = mapOf("ig_reels" to always)
        assertFalse(GrayPolicy.shouldBeGray(settings, "com.instagram.android", "ig_feed", noon))
    }

    @Test
    fun `a whole app goes grey on every screen, named or not`() {
        val settings = mapOf("com.instagram.android" to always)
        assertTrue(GrayPolicy.shouldBeGray(settings, "com.instagram.android", null, noon))
        assertTrue(GrayPolicy.shouldBeGray(settings, "com.instagram.android", "ig_feed", noon))
        assertFalse(GrayPolicy.shouldBeGray(settings, "com.google.android.youtube", null, noon))
    }

    @Test
    fun `nothing set means nothing is grey`() {
        assertFalse(GrayPolicy.shouldBeGray(emptyMap(), "com.instagram.android", "ig_reels", noon))
        assertFalse(GrayPolicy.shouldBeGray(emptyMap(), null, null, noon))
    }

    // ---- hours -------------------------------------------------------------

    @Test
    fun `grey with hours holds only inside them`() {
        val settings = mapOf("ig_reels" to evenings)
        assertTrue(GrayPolicy.shouldBeGray(settings, "ig", "ig_reels", at(DayOfWeek.MONDAY, 21)))
        assertFalse(GrayPolicy.shouldBeGray(settings, "ig", "ig_reels", at(DayOfWeek.MONDAY, 19, 59)))
        // The end is not inside: it is the first minute back in colour.
        assertFalse(GrayPolicy.shouldBeGray(settings, "ig", "ig_reels", at(DayOfWeek.MONDAY, 23)))
    }

    @Test
    fun `hours that cross midnight hold through the night`() {
        val night = GraySetting(listOf(Window(22 * 60, 6 * 60)))
        val settings = mapOf("ig_reels" to night)
        assertTrue(GrayPolicy.shouldBeGray(settings, "ig", "ig_reels", at(DayOfWeek.TUESDAY, 23)))
        assertTrue(GrayPolicy.shouldBeGray(settings, "ig", "ig_reels", at(DayOfWeek.WEDNESDAY, 3)))
        assertFalse(GrayPolicy.shouldBeGray(settings, "ig", "ig_reels", at(DayOfWeek.WEDNESDAY, 12)))
    }

    @Test
    fun `hours on some days leave the others in colour`() {
        val weekdays = GraySetting(listOf(Window(8 * 60, 18 * 60, Days.WEEKDAYS)))
        val settings = mapOf("ig_reels" to weekdays)
        assertTrue(GrayPolicy.shouldBeGray(settings, "ig", "ig_reels", at(DayOfWeek.FRIDAY, 10)))
        assertFalse(GrayPolicy.shouldBeGray(settings, "ig", "ig_reels", at(DayOfWeek.SATURDAY, 10)))
    }

    @Test
    fun `an app that is grey all day beats a screen whose hours have not begun`() {
        // Whichever says grey wins: two settings never cancel each other out.
        val settings = mapOf(
            "com.instagram.android" to always,
            "ig_reels" to evenings,
        )
        assertTrue(GrayPolicy.shouldBeGray(settings, "com.instagram.android", "ig_reels", noon))
    }

    // ---- the change delay --------------------------------------------------

    @Test
    fun `switching grey off is loosening and switching it on is not`() {
        assertTrue(GrayPolicy.isLoosening(always, null))
        assertFalse(GrayPolicy.isLoosening(null, always))
        assertFalse(GrayPolicy.isLoosening(null, null))
    }

    @Test
    fun `cutting the hours down is loosening, widening them is not`() {
        assertTrue(GrayPolicy.isLoosening(always, evenings))
        assertFalse(GrayPolicy.isLoosening(evenings, always))
        val shorter = GraySetting(listOf(Window(20 * 60, 22 * 60)))
        assertTrue(GrayPolicy.isLoosening(evenings, shorter))
        assertFalse(GrayPolicy.isLoosening(shorter, evenings))
    }

    @Test
    fun `moving the hours elsewhere is loosening`() {
        val morning = GraySetting(listOf(Window(6 * 60, 9 * 60)))
        assertTrue(GrayPolicy.isLoosening(evenings, morning))
    }

    // ---- storage -----------------------------------------------------------

    @Test
    fun `settings survive being written and read back`() {
        val settings = mapOf(
            "ig_reels" to always,
            "yt_shorts" to GraySetting(listOf(Window(21 * 60, 9 * 60, Days.WEEKDAYS))),
        )
        assertEquals(settings, GrayCodec.decode(GrayCodec.encode(settings)))
    }

    @Test
    fun `damaged storage means colour, never grey stuck on`() {
        assertEquals(emptyMap<String, GraySetting>(), GrayCodec.decode("not json"))
        assertEquals(emptyMap<String, GraySetting>(), GrayCodec.decode(null))
        assertEquals(emptyMap<String, GraySetting>(), GrayCodec.decode(""))
    }
}
