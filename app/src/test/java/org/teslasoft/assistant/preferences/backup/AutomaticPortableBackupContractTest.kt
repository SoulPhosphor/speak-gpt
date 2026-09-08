package org.teslasoft.assistant.preferences.backup

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticPortableBackupContractTest {

    @Test
    fun automaticPathUsesPortableWriterAndNeverRawSnapshotWriter() {
        val controller = source("preferences/backup/AutoBackupController.kt")
        assertTrue(controller.contains("AutomaticPortableBackupWriter.create"))
        assertFalse(controller.contains("RecoveryBackupManager.createBackup"))
    }

    @Test
    fun automaticWriterUsesPortablePackageAndDoesNotRotateOrDeleteOldBackups() {
        val writer = source("preferences/backup/AutomaticPortableBackupWriter.kt")
        assertTrue(writer.contains("PortableRecoveryWriter.createPackage"))
        assertTrue(writer.contains("RecoveryFileNaming.automaticRecoveryPackage"))
        assertFalse(writer.contains("rotate("))
        assertFalse(writer.contains("listFiles"))
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/org/teslasoft/assistant/$relative"),
            File("app/src/main/java/org/teslasoft/assistant/$relative"),
            File(System.getProperty("user.dir"), "src/main/java/org/teslasoft/assistant/$relative"),
            File(System.getProperty("user.dir"), "app/src/main/java/org/teslasoft/assistant/$relative")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Missing production source: $relative")
    }
}
