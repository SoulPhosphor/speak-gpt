package org.teslasoft.assistant.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageVisibilityTest {

    private fun message(hidden: Boolean = false): HashMap<String, Any> =
        hashMapOf<String, Any>("message" to "hello", "isBot" to false).also {
            if (hidden) it[MessageVisibility.KEY] = true
        }

    @Test fun makeHiddenIsOfferedOnlyWhileTheSettingIsOn() {
        assertEquals(
            MessageVisibility.Action.MAKE_HIDDEN,
            MessageVisibility.menuAction(hidden = false, allowHiding = true, condensed = false)
        )
        assertNull(MessageVisibility.menuAction(hidden = false, allowHiding = false, condensed = false))
    }

    @Test fun makeVisibleStaysOnAHiddenMessageAfterTheSettingIsTurnedOff() {
        assertEquals(
            MessageVisibility.Action.MAKE_VISIBLE,
            MessageVisibility.menuAction(hidden = true, allowHiding = false, condensed = false)
        )
    }

    @Test fun condensedMessagesOfferNeitherAction() {
        assertNull(MessageVisibility.menuAction(hidden = true, allowHiding = true, condensed = true))
        assertNull(MessageVisibility.menuAction(hidden = false, allowHiding = true, condensed = true))
    }

    @Test fun storedStringTrueReadsAsHidden() {
        assertTrue(MessageVisibility.isHidden(mapOf(MessageVisibility.KEY to "true")))
        assertFalse(MessageVisibility.isHidden(mapOf("message" to "x")))
    }

    @Test fun toggleRollsBackWhenTheSaveFails() {
        val m = message()
        assertFalse(MessageVisibility.toggleAndSave(m) { false })
        assertFalse(MessageVisibility.isHidden(m))
        assertTrue(MessageVisibility.toggleAndSave(m) { true })
        assertTrue(MessageVisibility.isHidden(m))
        assertTrue(MessageVisibility.toggleAndSave(m) { true })
        assertFalse(m.containsKey(MessageVisibility.KEY))
    }

    @Test fun showAllClearsEveryMarkAndRestoresThemOnFailure() {
        val list = listOf(message(hidden = true), message(), message(hidden = true))
        assertEquals(2, MessageVisibility.hiddenCount(list))
        assertFalse(MessageVisibility.showAllAndSave(list) { false })
        assertEquals(2, MessageVisibility.hiddenCount(list))
        assertTrue(MessageVisibility.showAllAndSave(list) { true })
        assertEquals(0, MessageVisibility.hiddenCount(list))
    }
}
