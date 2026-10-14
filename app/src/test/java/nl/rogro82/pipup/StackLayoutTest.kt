package nl.rogro82.pipup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// Popup stacking (0.27.0, #64): offsets per popup and which popups stack at all.
class StackLayoutTest {

    @Test
    fun edgeStackAddsUpSizesFromTheMargin() {
        // three popups 300, 200 and 100 px tall against the top edge, 20 px margin, 12 px gap
        assertEquals(listOf(20, 332, 544), StackLayout.offsets(listOf(300, 200, 100), centred = false, margin = 20, gap = 12))
    }

    @Test
    fun singleEdgePopupKeepsTheClassicMargin() {
        assertEquals(listOf(20), StackLayout.offsets(listOf(400), centred = false, margin = 20, gap = 12))
    }

    @Test
    fun centredStackKeepsTheFirstCentredAndLinesUpTheRestAfterIt() {
        // 200 px centred, then 100 px: its centre sits (200 + 100) / 2 + 12 = 162 px further
        assertEquals(listOf(0, 162, 324), StackLayout.offsets(listOf(200, 100, 200), centred = true, margin = 20, gap = 12))
    }

    @Test
    fun unknownSizeCountsAsZeroUntilLaidOut() {
        assertEquals(listOf(20, 332), StackLayout.offsets(listOf(300, 0), centred = false, margin = 20, gap = 12))
    }

    @Test
    fun stackValueMapsOntoAnAxis() {
        assertEquals(PopupProps.StackAxis.Vertical, PopupProps(stack = "vertical").stackAxis)
        assertEquals(PopupProps.StackAxis.Horizontal, PopupProps(stack = "Horizontal").stackAxis)
        assertNull(PopupProps(stack = "none").stackAxis)
        assertNull(PopupProps().stackAxis)
    }

    @Test
    fun centerNeverStacks() {
        assertNull(PopupProps(stack = "vertical", position = PopupProps.Position.Center).stackAxis)
    }
}
