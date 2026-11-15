package org.teslasoft.assistant.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardPositionHoldTest {

    private val nav = 48
    private val keyboard = 900
    private val pageHeight = 2000

    @Test
    fun openingKeyboardKeepsBottomLineJustAboveKeyboard() {
        // Closed: scroll area runs behind the nav bar.
        val held = KeyboardPositionHold.visibleBottom(scrollY = 500, height = pageHeight, hiddenBottom = nav)
        // Open: area ends at the keyboard's top edge and nothing is hidden.
        val newScroll = KeyboardPositionHold.heldScrollY(held, height = pageHeight - keyboard, hiddenBottom = 0)
        assertEquals(500 + keyboard - nav, newScroll)
        assertEquals(held, KeyboardPositionHold.visibleBottom(newScroll, pageHeight - keyboard, 0))
    }

    @Test
    fun closingKeyboardBringsContentBackDown() {
        val openScroll = 500 + keyboard - nav
        val held = KeyboardPositionHold.visibleBottom(openScroll, pageHeight - keyboard, 0)
        assertEquals(500, KeyboardPositionHold.heldScrollY(held, pageHeight, nav))
    }

    @Test
    fun pageAtTopNeedsToScrollOnlyByCoveredAmount() {
        val held = KeyboardPositionHold.visibleBottom(0, pageHeight, nav)
        // The view clamps this to its scroll range, so a short page moves
        // only as far as its content requires.
        assertEquals(keyboard - nav, KeyboardPositionHold.heldScrollY(held, pageHeight - keyboard, 0))
    }
}
