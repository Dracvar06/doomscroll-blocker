package cat.doorman.app

import cat.doorman.app.rules.Arrival
import cat.doorman.app.service.Foreground
import cat.doorman.app.service.WindowFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which app is really in front, read from the window list.
 *
 * These are the shapes the list took on a Pixel while a swipe home was started
 * and not finished, and while YouTube sat in its floating window.
 */
class ForegroundTest {

    private val ignore = setOf("com.android.systemui", "android", "cat.doorman.app", "com.keyboard")

    private fun app(
        pkg: String?,
        active: Boolean = false,
        focused: Boolean = active,
        pip: Boolean = false,
        application: Boolean = true,
    ) = WindowFact(pkg, application, active, focused, pip)

    @Test
    fun `an empty list says nothing`() {
        val result = Foreground.resolve(emptyList(), ignore)
        assertNull(result.appPackage)
        assertEquals(emptyList<WindowFact>(), result.pip)
    }

    @Test
    fun `the active app window is the foreground`() {
        val result = Foreground.resolve(
            listOf(app("com.android.systemui"), app("com.google.android.youtube", active = true)),
            ignore,
        )
        assertEquals("com.google.android.youtube", result.appPackage)
    }

    /**
     * The late launcher event after a cancelled swipe: the launcher claims the
     * foreground, but the list has YouTube active and the launcher not.
     */
    @Test
    fun `a launcher that is not active is not the foreground`() {
        val result = Foreground.resolve(
            listOf(
                app("com.google.android.youtube", active = true),
                app("com.google.android.apps.nexuslauncher"),
            ),
            ignore,
        )
        assertEquals("com.google.android.youtube", result.appPackage)
    }

    @Test
    fun `the launcher is the foreground while it is active`() {
        val result = Foreground.resolve(
            listOf(
                app("com.google.android.youtube"),
                app("com.google.android.apps.nexuslauncher", active = true),
            ),
            ignore,
        )
        assertEquals("com.google.android.apps.nexuslauncher", result.appPackage)
    }

    @Test
    fun `a floating window is not the foreground and is reported on its own`() {
        val result = Foreground.resolve(
            listOf(
                app("com.google.android.youtube", pip = true),
                app("com.google.android.apps.nexuslauncher", active = true),
            ),
            ignore,
        )
        assertEquals("com.google.android.apps.nexuslauncher", result.appPackage)
        assertEquals(listOf("com.google.android.youtube"), result.pip.map { it.packageName })
    }

    @Test
    fun `a floating window that is somehow active is still not the foreground`() {
        val result = Foreground.resolve(
            listOf(app("com.google.android.youtube", active = true, pip = true)),
            ignore,
        )
        assertNull(result.appPackage)
        assertEquals(1, result.pip.size)
    }

    @Test
    fun `the shade, the keyboard and Doorman never count`() {
        val result = Foreground.resolve(
            listOf(
                app("com.android.systemui", active = true),
                app("com.keyboard", active = true),
                app("cat.doorman.app", active = true),
                app("com.google.android.youtube"),
            ),
            ignore,
        )
        assertNull(result.appPackage)
    }

    /** No fallback to the focused window: the app being left can still hold focus mid-opening. */
    @Test
    fun `nothing active means nothing to report even if something is focused`() {
        val result = Foreground.resolve(
            listOf(app("com.google.android.youtube", active = false, focused = true)),
            ignore,
        )
        assertNull(result.appPackage)
    }

    @Test
    fun `windows that are not applications are ignored`() {
        val result = Foreground.resolve(
            listOf(app("com.google.android.youtube", active = true, application = false)),
            ignore,
        )
        assertNull(result.appPackage)
    }

    @Test
    fun `a window with no package is ignored`() {
        val result = Foreground.resolve(listOf(app(null, active = true)), ignore)
        assertNull(result.appPackage)
    }

    // ---- coming back from a swipe that was not finished

    private val launchers = setOf("com.google.android.apps.nexuslauncher")

    @Test
    fun `coming back from the launcher moments later keeps how the app was reached`() {
        val arrival = Arrival.arrivalAfterReturn(
            previous = "com.google.android.apps.nexuslauncher",
            current = "com.google.android.youtube",
            leftApp = "com.google.android.youtube",
            arrivalBeforeLeaving = "com.whatsapp",
            sinceLeftMillis = 1_500,
            launcherPackages = launchers,
        )
        assertEquals("com.whatsapp", arrival)
    }

    @Test
    fun `coming back after a long time is a fresh visit`() {
        val arrival = Arrival.arrivalAfterReturn(
            previous = "com.google.android.apps.nexuslauncher",
            current = "com.google.android.youtube",
            leftApp = "com.google.android.youtube",
            arrivalBeforeLeaving = "com.whatsapp",
            sinceLeftMillis = 60_000,
            launcherPackages = launchers,
        )
        assertEquals("com.google.android.apps.nexuslauncher", arrival)
    }

    @Test
    fun `a different app after the launcher is a fresh visit`() {
        val arrival = Arrival.arrivalAfterReturn(
            previous = "com.google.android.apps.nexuslauncher",
            current = "com.instagram.android",
            leftApp = "com.google.android.youtube",
            arrivalBeforeLeaving = "com.whatsapp",
            sinceLeftMillis = 1_000,
            launcherPackages = launchers,
        )
        assertEquals("com.google.android.apps.nexuslauncher", arrival)
    }

    @Test
    fun `arriving from an ordinary app is untouched`() {
        val arrival = Arrival.arrivalAfterReturn(
            previous = "com.whatsapp",
            current = "com.google.android.youtube",
            leftApp = null,
            arrivalBeforeLeaving = null,
            sinceLeftMillis = 100,
            launcherPackages = launchers,
        )
        assertEquals("com.whatsapp", arrival)
    }
}
