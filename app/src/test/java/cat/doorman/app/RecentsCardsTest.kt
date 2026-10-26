package cat.doorman.app

import cat.doorman.app.model.ScreenSnapshot
import cat.doorman.app.model.UiNode
import cat.doorman.app.rules.EntryButtons
import cat.doorman.app.rules.RecentsCards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The recents card of a blocked app is found by the name the launcher reads
 * out for it, and only something card-sized counts. Bounds are the Pixel 10's
 * recents screen with Instagram in the middle.
 */
class RecentsCardsTest {

    private fun node(desc: String?, bounds: String) =
        UiNode(contentDescription = desc, bounds = bounds, visible = true)

    private fun find(vararg nodes: UiNode, label: String = "Instagram") =
        RecentsCards.find(ScreenSnapshot(packageName = "launcher", nodes = nodes.toList()), label, 1080, 2424)

    @Test
    fun `the card in the middle of recents is found`() {
        assertEquals(EntryButtons.Box(173, 317, 907, 1964), find(node("Instagram", "173,317,907,1964")))
    }

    @Test
    fun `the home screen icon of the same name is not a card`() {
        assertNull(find(node("Instagram", "40,1800,240,2050")))
    }

    @Test
    fun `a card half off the screen is covered where it shows`() {
        assertEquals(EntryButtons.Box(0, 317, 500, 1964), find(node("Instagram", "-300,317,500,1964")))
        assertEquals(EntryButtons.Box(0, 317, 131, 1964), find(node("Instagram", "-603,317,131,1964")))
        assertEquals(EntryButtons.Box(946, 317, 1080, 1964), find(node("Instagram", "946,317,1080,1964")))
        assertNull(find(node("Instagram", "1100,317,1834,1964")))
    }

    @Test
    fun `another app's card is left alone`() {
        assertNull(find(node("Play Books", "173,317,907,1964")))
        assertNull(find(node("Instagrammer", "173,317,907,1964")))
        assertNull(find(node(null, "173,317,907,1964")))
    }
}
