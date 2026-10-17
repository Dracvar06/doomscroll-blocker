package cat.doorman.app

import cat.doorman.app.block.PipHole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mini player has to stay closable, and the cover has to hide the picture.
 * The window sizes are the ones measured on a Pixel 10 (density 2.625).
 */
class PipHoleTest {

    private val density = 2.625f

    @Test
    fun `the hole is the top right corner`() {
        val hole = PipHole.hole(379, 673, density)
        assertEquals(379, hole.right)
        assertEquals(0, hole.top)
        assertEquals(147, hole.width)
        assertEquals(147, hole.height)
    }

    /** The close button measured 64px from the right and 61px down: well inside. */
    @Test
    fun `the measured close button is inside the hole`() {
        val hole = PipHole.hole(379, 673, density)
        val x = 379 - 64
        val y = 61
        assertTrue(x in hole.left..hole.right)
        assertTrue(y in hole.top..hole.bottom)
        // and so is a touch target of 48dp centred on it
        val half = (24 * density).toInt()
        assertTrue(x - half >= hole.left - 8 && y + half <= hole.bottom + 8)
    }

    @Test
    fun `a wide window gets the same corner`() {
        val hole = PipHole.hole(640, 360, density)
        assertEquals(640, hole.right)
        assertEquals(0, hole.top)
        assertEquals(147, hole.width)
    }

    @Test
    fun `a tiny window is never mostly open`() {
        val hole = PipHole.hole(200, 120, density)
        assertTrue(hole.width <= 120 * 60 / 100)
        assertTrue(hole.height <= 120 * 60 / 100)
        assertTrue(hole.left >= 0)
    }

    @Test
    fun `a degenerate window has no hole and does not crash`() {
        val hole = PipHole.hole(0, 0, density)
        assertEquals(0, hole.width)
        assertEquals(0, hole.height)
    }

    @Test
    fun `everything but the hole is covered`() {
        val hole = PipHole.hole(379, 673, density)
        val boxes = PipHole.covered(379, 673, hole)
        val area = boxes.sumOf { it.width * it.height }
        assertEquals(379 * 673 - hole.width * hole.height, area)
        // no covered box overlaps the hole
        boxes.forEach { b ->
            val overlaps = b.left < hole.right && b.right > hole.left &&
                b.top < hole.bottom && b.bottom > hole.top
            assertTrue("$b overlaps the hole", !overlaps)
        }
    }

    @Test
    fun `with no hole the whole window is covered`() {
        val hole = PipHole.hole(0, 0, density)
        assertEquals(emptyList<PipHole.Box>(), PipHole.covered(0, 0, hole))
        val full = PipHole.covered(100, 100, PipHole.Box(100, 0, 100, 0))
        assertEquals(100 * 100, full.sumOf { it.width * it.height })
    }
}
