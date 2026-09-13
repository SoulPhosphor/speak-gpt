package org.teslasoft.assistant.preferences.backup

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDatabaseRestoreContractTest {
    @Test
    fun journalCarriesRequiredNonSecretEvidenceAndAllPhases() {
        val journal = source("preferences/backup/DirectDatabaseRestoreJournal.kt")
        for (field in listOf(
            "database_type",
            "source_kind",
            "source_identity_sha256",
            "active_path",
            "staged_path",
            "quarantine_path",
            "original_key",
            "intended_key",
            "original_files",
            "installed_files"
        )) assertTrue(field, journal.contains("\"$field\""))
        for (phase in listOf("PREPARED", "KEY_SWITCHED", "FILE_INSTALLED", "VERIFIED", "COMMITTED")) {
            assertTrue(phase, journal.contains(phase))
        }
        assertFalse(journal.contains("databaseKeyHex"))
        assertFalse(journal.contains("key_bytes"))
    }

    @Test
    fun publicationIsAtomicAndHasNoDeleteThenCopyFallback() {
        val coordinator = source("preferences/backup/DirectDatabaseRestoreCoordinator.kt")
        val operations = source("preferences/backup/DurableRecoveryFileOps.kt")
        assertTrue(operations.contains("StandardCopyOption.ATOMIC_MOVE"))
        assertTrue(operations.contains("StandardCopyOption.REPLACE_EXISTING"))
        val publish = coordinator.substringAfter("private fun installLocked(")
            .substringAfter("AFTER_SIDECAR_REMOVAL")
            .substringBefore("AFTER_FILE_RENAME")
        assertTrue(publish.contains("DurableRecoveryFileOps.atomicReplace(staged, active)"))
        assertFalse(publish.contains("copyTo(active"))
        assertFalse(publish.contains("deleteActiveFiles"))
    }

    @Test
    fun journalPrecedesKeyAndFileMutationAndNormalStorePrecedesCommit() {
        val coordinator = source("preferences/backup/DirectDatabaseRestoreCoordinator.kt")
        val body = coordinator.substringAfter("private fun installLocked(")
            .substringBefore("private fun recoverOne(")
        val prepared = body.indexOf("DirectDatabaseRestoreJournal.write(context, record)")
        val key = body.indexOf("installKey(context")
        val publish = body.indexOf("DurableRecoveryFileOps.atomicReplace(staged, active)")
        val normal = body.indexOf("verifyThroughNormalStore(context, type)")
        val verified = body.indexOf("phase = Phase.VERIFIED")
        val committed = body.indexOf("phase = Phase.COMMITTED")
        val retired = body.indexOf("retireCommitted(context, record)")
        assertTrue(prepared >= 0 && key > prepared && publish > key)
        assertTrue(normal > publish && verified > normal && committed > verified && retired > committed)
    }

    @Test
    fun startupFinishAlsoMarksVerifiedOnlyAfterTheNormalStoreCheck() {
        val coordinator = source("preferences/backup/DirectDatabaseRestoreCoordinator.kt")
        val body = coordinator.substringAfter("private fun finishInstall(")
            .substringBefore("private fun rollback(")
        val normal = body.indexOf("verifyThroughNormalStore(context, record.type)")
        val verified = body.indexOf("phase = Phase.VERIFIED")
        assertTrue(normal >= 0 && verified > normal)
    }

    @Test
    fun startupRecoveryRunsBeforeUnifiedRecoveryAndStoreHousekeeping() {
        val application = source("app/MainApplication.kt")
        val direct = application.indexOf("DirectDatabaseRestoreCoordinator.recoverAll(this)")
        val profile = application.indexOf("DirectProfileImageRestoreRecovery.recoverPending(this)")
        val unified = application.indexOf("UnifiedPortableRestoreCoordinator.recoverPending(this)")
        val memory = application.indexOf("MemoryStore.getInstance(this)")
        assertTrue(direct >= 0 && profile > direct && unified > profile && memory > unified)
    }

    @Test
    fun oneGateCoversDirectRestoreRepairPortableBackupAndUnifiedApply() {
        assertTrue(source("preferences/backup/DirectDatabaseRestoreCoordinator.kt")
            .contains("RecoveryOperationGate.runExclusive"))
        assertTrue(source("preferences/backup/DatabaseRepairManager.kt")
            .contains("RecoveryOperationGate.runExclusive"))
        assertTrue(source("preferences/backup/portable/PortableRecoveryWriter.kt")
            .contains("RecoveryOperationGate.runExclusive"))
        assertTrue(source("preferences/backup/portable/UnifiedPortableRestoreCoordinator.kt")
            .contains("RecoveryOperationGate.runExclusive"))
    }

    @Test
    fun legacyMemoryAndDirectProfileUseDurableOwners() {
        assertTrue(source("preferences/backup/DatabaseRevertManager.kt")
            .contains("DirectDatabaseRestoreCoordinator.install"))
        val restore = source("preferences/backup/DatabaseRestoreManager.kt")
        assertTrue(restore.contains("DirectProfileImageRestoreRecovery.execute"))
        assertFalse(restore.contains("profile_image_participant_journal"))
    }

    private fun source(relative: String): String = mainRoot().resolve(relative).readText()

    private fun mainRoot(): File = listOf(
        File("src/main/java/org/teslasoft/assistant"),
        File("app/src/main/java/org/teslasoft/assistant")
    ).firstOrNull(File::isDirectory) ?: error("main source root unavailable")
}
