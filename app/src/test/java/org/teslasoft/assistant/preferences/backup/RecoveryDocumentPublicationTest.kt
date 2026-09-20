package org.teslasoft.assistant.preferences.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryDocumentPublicationTest {
    @Test
    fun incompleteNameCannotLookLikeAFinalRecognizedBackup() {
        val finalName = "Phosphor-Automatic-Recovery-Protected-2026-09-14_0100.zip"
        val incomplete = RecoveryDocumentPublication.incompleteName(finalName)
        assertEquals("$finalName.incomplete", incomplete)
        assertFalse(RecoveryFileNaming.hasAutomaticRecoveryShape(incomplete))
        assertTrue(RecoveryFileNaming.hasAutomaticRecoveryShape(finalName))
    }
}
