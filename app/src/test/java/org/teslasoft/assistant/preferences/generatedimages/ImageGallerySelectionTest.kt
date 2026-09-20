package org.teslasoft.assistant.preferences.generatedimages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGallerySelectionTest {
    @Test fun lockedImagesStayUnselectableAndStaleLocksAreRemoved() {
        val selection = ImageGallerySelection()
        val unlocked = record("one", false)
        assertTrue(selection.toggle(unlocked))
        assertEquals(setOf("one"), selection.ids())
        assertFalse(selection.toggle(record("two", true)))
        selection.retainEligible(listOf(unlocked.copy(locked = true), record("two", true)))
        assertTrue(selection.ids().isEmpty())
    }

    private fun record(id: String, locked: Boolean) = GeneratedImageCatalogRecord(
        id, "hash-$id", "$id.png", "image/png", 1, 1, 1, null, null, null, locked
    )
}
