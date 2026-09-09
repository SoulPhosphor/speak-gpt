package org.teslasoft.assistant.preferences.backup.portable

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreProvisioningStateTest {
    @Test
    fun markerSurvivesParticipantReconstruction() {
        val root = Files.createTempDirectory("restore-provisioning").toFile()
        try {
            assertNull(RestoreProvisioningState.read(root))
            assertTrue(RestoreProvisioningState.write(root, false))
            assertEquals(false, RestoreProvisioningState.read(root))
            assertTrue(RestoreProvisioningState.write(root, true))
            assertEquals(true, RestoreProvisioningState.read(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
