package cat.doorman.app

import cat.doorman.app.limits.ReelQuota
import cat.doorman.app.limits.ReelWindow
import cat.doorman.app.service.ReelCounter
import cat.doorman.app.service.ScrollBudget
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One swipe on RedNote's video viewer is one video, and the scroll events of
 * arriving at it are none.
 *
 * The viewer is a full-screen vertical RecyclerView (2424px on the test phone)
 * with the next video already laid out below. The events here are *modelled*
 * on that shape, not captured: the phone this was built against had RedNote
 * blocked outright, so a real swipe could not be taken from it. The two
 * arrival events are the ones its log showed when RedNote's Explore list came
 * up (no delta, an index appearing). Replace the sequences with a capture when
 * one is taken.
 *
 * [Replay] is the service's `onReelScroll` path without the Android types: the
 * same budget of one per swipe, the same source key, the same refractory
 * window and pager-size check, the same counter calls.
 */
class RedNoteSwipeReplayTest {

    private data class Scroll(
        val at: Long,
        val dX: Int,
        val dY: Int,
        val to: Int,
        val source: String = RECYCLER,
        val viewHeight: Int? = PAGER_HEIGHT,
    )

    private val quota = ReelQuota(10, 60)
    private val saved = mutableMapOf<String, ReelWindow>()
    private val persist: (String, ReelWindow) -> Unit = { id, w -> saved[id] = w }

    private inner class Replay {
        val counter = ReelCounter()
        val swipes = ScrollBudget(viewportHeightPx = { SCREEN_HEIGHT })
        val key = "com.xingin.xhs/rn_video_pager"
        var swipesSeen = 0

        /** The evaluation that puts the viewer in front: the first look counts one video. */
        fun arrive(at: Long) {
            swipes.begin(key, 1, at)
            counter.onScreen("rn_video_pager", quota, at, persist)
        }

        /** Another look at the same screen, as every content event is. */
        fun look(at: Long) {
            swipes.begin(key, 1, at)
            counter.onScreen("rn_video_pager", quota, at, persist)
        }

        fun scroll(e: Scroll) {
            if (counter.settling(e.at)) return
            if (!ReelCounter.isPagerSized(e.viewHeight, SCREEN_HEIGHT)) return
            val swiped = swipes.onScroll(
                key = key,
                deltaX = e.dX,
                deltaY = e.dY,
                itemIndex = e.to.takeIf { it >= 0 },
                nowMillis = e.at,
                scrolledViewHeightPx = e.viewHeight,
                source = e.source,
            )
            if (!swiped) return
            swipes.reset()
            swipes.begin(key, 1, e.at)
            swipesSeen++
            counter.onSwipe("rn_video_pager", quota, e.at, persist)
        }

        val count: Int get() = saved["rn_video_pager"]?.count ?: 0
    }

    /** Arriving: the list reports no movement, then the position it opened at. */
    private val arrival = listOf(
        Scroll(10, 0, 0, -1),
        Scroll(310, 0, 0, 5),
    )

    /** One page turn: movement of a full page, the index moving on, and the pager settling. */
    private val oneSwipe = listOf(
        Scroll(5_000, 0, 1_212, 5),
        Scroll(5_016, 0, 1_212, 6),
        Scroll(5_120, 0, 0, 6),
        Scroll(5_330, 0, 0, 6),
    )

    @Test
    fun `the events of arriving count no swipe`() {
        val r = Replay()
        r.arrive(0)
        arrival.forEach { r.scroll(it) }
        r.look(500)
        assertEquals(0, r.swipesSeen)
        // The one video that was opened.
        assertEquals(1, r.count)
    }

    @Test
    fun `one swipe is exactly one more video`() {
        val r = Replay()
        r.arrive(0)
        arrival.forEach { r.scroll(it) }
        oneSwipe.forEach { r.scroll(it) }
        r.look(5_500)
        assertEquals(1, r.swipesSeen)
        assertEquals(2, r.count)
    }

    @Test
    fun `a second swipe after the first has settled is the next video`() {
        val r = Replay()
        r.arrive(0)
        arrival.forEach { r.scroll(it) }
        oneSwipe.forEach { r.scroll(it) }
        oneSwipe.map { it.copy(at = it.at + 4_000, to = it.to + 1) }.forEach { r.scroll(it) }
        assertEquals(2, r.swipesSeen)
        assertEquals(3, r.count)
    }

    @Test
    fun `scrolling inside the comments sheet or a small view is not a swipe`() {
        val r = Replay()
        r.arrive(0)
        repeat(6) { i ->
            r.scroll(Scroll(2_000L + i * 50, 0, 300, i, source = "androidx.recyclerview.widget.RecyclerView", viewHeight = 900))
        }
        assertEquals(0, r.swipesSeen)
        assertEquals(1, r.count)
    }

    @Test
    fun `a sideways drag is not a swipe`() {
        val r = Replay()
        r.arrive(0)
        r.scroll(Scroll(2_000, 900, 40, 5))
        r.scroll(Scroll(2_050, 900, 40, 6))
        assertEquals(0, r.swipesSeen)
    }

    @Test
    fun `indices from two views are never compared with each other`() {
        val r = Replay()
        r.arrive(0)
        r.scroll(Scroll(2_000, 0, 0, 5))
        r.scroll(Scroll(2_100, 0, 0, 0, source = "androidx.viewpager2.widget.ViewPager2"))
        r.scroll(Scroll(2_200, 0, 0, 5))
        assertEquals(0, r.swipesSeen)
    }

    private companion object {
        const val RECYCLER = "androidx.recyclerview.widget.RecyclerView"
        const val SCREEN_HEIGHT = 2424
        const val PAGER_HEIGHT = 2424
    }
}
