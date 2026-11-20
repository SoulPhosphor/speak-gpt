package org.teslasoft.assistant.preferences.backup

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One automatic backup system: the legacy memory export no longer runs at app
 * start, and the damaged-memory Revert restores memory alone from Automatic
 * Recovery Backups as well as from the existing memory backups.
 */
class BackupConsolidationContractTest {

    private fun source(relative: String): String {
        val path = "src/main/java/org/teslasoft/assistant/$relative"
        return listOf(File(path), File("app/$path")).first { it.isFile }.readText()
    }

    @Test fun appStartNoLongerRunsTheLegacyMemoryExport() {
        assertFalse(source("app/MainApplication.kt").contains("autoExportIfDue"))
        assertFalse(source("preferences/memory/MemoryExporter.kt").contains("fun autoExportIfDue"))
        // Automatic Recovery Backups still run as before.
        assertTrue(source("app/MainApplication.kt").contains("AutoBackupController.runIfDue(this)"))
    }

    @Test fun memoryRevertUsesEveryBackupSourceAndRestoresMemoryOnly() {
        val flows = source("ui/DatabaseRecoveryFlows.kt")
        assertTrue(flows.contains("DatabaseRestoreManager.prepareLastGood(context, type)"))
        assertTrue(flows.contains("DatabaseRestoreManager.restore(activity.applicationContext, source)"))
        assertTrue(flows.contains("if (type != BackupType.MEMORY) return null"))
        val manager = source("preferences/backup/DatabaseRestoreManager.kt")
        assertTrue(manager.contains("listAutomaticPackages(context, autoUri)"))
        // The legacy JSON chain stays a restore source.
        assertTrue(manager.contains("DatabaseRevertManager.listCandidates(context, type)"))
    }

    @Test fun resetMemoriesStillRequiresAVerifiedBackup() {
        val screen = source("ui/activities/MemoryBackupRestoreActivity.kt")
        val gate = screen.indexOf("backupName = MemoryExporter.writeBackupNow(this)")
        val refuse = screen.indexOf("return@runOffThread", gate)
        val reset = screen.indexOf("MemoryStore.getInstance(this).resetAllMemoryData()", gate)
        assertTrue(gate in 0 until refuse && refuse < reset)
    }

    @Test fun automaticRecoveryBackupNamesAreRecognizedOnlyWhenFinished() {
        val name = RecoveryFileNaming.automaticRecoveryPackage("Phosphor Shines Beta", true, 1_763_600_000_000L)
        assertTrue(DatabaseRestoreManager.isAutomaticRecoveryPackageName(name))
        assertFalse(DatabaseRestoreManager.isAutomaticRecoveryPackageName(RecoveryDocumentPublication.incompleteName(name)))
        assertFalse(DatabaseRestoreManager.isAutomaticRecoveryPackageName("memory-1763600000000.dbbackup"))
        assertFalse(DatabaseRestoreManager.isAutomaticRecoveryPackageName("memory-export-2026-11-20T00-00-00Z.json"))
    }
}
