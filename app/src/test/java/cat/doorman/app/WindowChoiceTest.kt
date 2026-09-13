package cat.doorman.app

import cat.doorman.app.model.SnapshotCapture.WindowChoice
import cat.doorman.app.model.SnapshotCapture.preferredWindowIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which window a blocking decision gets made about.
 *
 * Both ways of getting this wrong are silent. Reading the wrong app's window
 * blocks nothing and looks like a broken rule; reading the right app's *scrap*
 * window does the same, and that is what happened on Reddit.
 */
class WindowChoiceTest {

    @Test
    fun `nothing to choose from`() {
        assertNull(preferredWindowIndex(emptyList(), "com.reddit.frontpage"))
    }

    /**
     * The one that actually broke. Reddit keeps a three-pixel pop-up window
     * above its feed, and it comes first. Its tree has three nodes in it, so
     * the home feed went unrecognised and unblocked.
     */
    @Test
    fun `an app's scrap window loses to the app's real one`() {
        val windows = listOf(
            WindowChoice("com.reddit.frontpage", active = false, focused = false),
            WindowChoice("com.reddit.frontpage", active = true, focused = true),
        )
        assertEquals(1, preferredWindowIndex(windows, "com.reddit.frontpage"))
    }

    /**
     * And the case the preference was added for in the first place: while an
     * app opens, systemui owns an application window that is both active and
     * focused. The app coming to the front still wins.
     */
    @Test
    fun `the app being opened beats an active system window`() {
        val windows = listOf(
            WindowChoice("com.android.systemui", active = true, focused = true),
            WindowChoice("com.instagram.android", active = false, focused = false),
        )
        assertEquals(1, preferredWindowIndex(windows, "com.instagram.android"))
    }

    @Test
    fun `with nothing preferred, the active focused window wins`() {
        val windows = listOf(
            WindowChoice("com.example.one", active = false, focused = false),
            WindowChoice("com.example.two", active = true, focused = true),
        )
        assertEquals(1, preferredWindowIndex(windows, null))
    }

    @Test
    fun `a preference nothing matches does not stop a choice being made`() {
        val windows = listOf(WindowChoice("com.example.one", active = true, focused = true))
        assertEquals(0, preferredWindowIndex(windows, "com.absent.app"))
    }
}
