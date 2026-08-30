package org.teslasoft.assistant.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteImagesWithChatPreferenceTest {
    @Test fun defaultIsOffAndValueIsAppWide() {
        val global = FakeSharedPreferences()
        val first = Preferences(FakeSharedPreferences(), global, "one")
        val second = Preferences(FakeSharedPreferences(), global, "two")
        assertFalse(first.getDeleteImagesWithChat())
        first.setDeleteImagesWithChat(true)
        assertTrue(second.getDeleteImagesWithChat())
        second.setDeleteImagesWithChat(false)
        assertFalse(first.getDeleteImagesWithChat())
    }
}
