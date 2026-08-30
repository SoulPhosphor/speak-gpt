package org.teslasoft.assistant.preferences.generatedimages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGalleryActionPolicyTest {
    @Test fun originAndLockStateControlOnlyTheirOwnActions() {
        val unlocked = record(locked = false)
        val available = ImageGalleryActionPolicy.forRecord(unlocked, originChatExists = true)
        assertTrue(available.canGoToChat)
        assertTrue(available.canDelete)
        assertEquals(ImageGalleryActionPolicy.LockAction.LOCK, available.lockAction)

        val lockedOrphan = ImageGalleryActionPolicy.forRecord(unlocked.copy(locked = true), false)
        assertFalse(lockedOrphan.canGoToChat)
        assertFalse(lockedOrphan.canDelete)
        assertTrue(lockedOrphan.canAddToAvatarGallery)
        assertEquals(ImageGalleryActionPolicy.LockAction.UNLOCK, lockedOrphan.lockAction)
    }

    private fun record(locked: Boolean) = GeneratedImageCatalogRecord(
        "id", "hash", "image.png", "image/png", 1, 1, 1, "chat", "Chat", "message", locked
    )
}
