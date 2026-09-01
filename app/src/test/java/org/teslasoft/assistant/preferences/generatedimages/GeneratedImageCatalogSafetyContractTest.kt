package org.teslasoft.assistant.preferences.generatedimages

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedImageCatalogSafetyContractTest {

    @Test
    fun maintenanceBackfillsBeforeAnyReconciliation() {
        val source = source("preferences/generatedimages/GeneratedImageCatalogBackfill.kt")
        val maintenance = source.substringAfter("object GeneratedImageCatalogMaintenance")
        assertTrue(
            maintenance.indexOf("GeneratedImageCatalogBackfill.run") <
                maintenance.indexOf("GeneratedImageCatalogReconciler.run")
        )
    }

    @Test
    fun reconciliationNeverDeletesByUuidFilenameShape() {
        val source = source("preferences/generatedimages/GeneratedImageCatalogBackfill.kt")
        val reconciler = source.substringAfter("object GeneratedImageCatalogReconciler")
            .substringBefore("object GeneratedImageCatalogMaintenance")
        assertFalse(reconciler.contains("canonicalPattern"))
        assertFalse(reconciler.contains("listFiles()?.filter"))
        assertFalse(reconciler.contains("it.delete()"))
        assertTrue(reconciler.contains("GeneratedImageRegistrationJournal.recover"))
    }

    @Test
    fun backfillUsesPersistedMessageUuid() {
        val source = source("preferences/generatedimages/GeneratedImageCatalogBackfill.kt")
        assertTrue(source.contains("SearchableMessageProjection.MESSAGE_ID_KEY"))
        assertFalse(source.contains("originMessageId = message[\"id\"]"))
    }

    @Test
    fun missingCatalogStateCannotMasqueradeAsAvailable() {
        val health = source("preferences/generatedimages/GeneratedImageCatalogHealth.kt")
        val store = source("preferences/generatedimages/GeneratedImageCatalogStore.kt")
        assertTrue(health.contains("NEEDS_RECOVERY"))
        assertTrue(health.contains("missingDatabaseRequiresRecovery"))
        assertTrue(store.contains("markNeedsRecovery"))
        assertTrue(store.contains("if (recoveryState && !allowRecoveryBackfill)"))
    }

    @Test
    fun registrationCleanupRequiresDurableExactFileProof() {
        val journal = source("preferences/generatedimages/GeneratedImageRegistrationJournal.kt")
        val registry = source("imagegen/ImageGenerationJobRegistry.kt")
        assertTrue(journal.contains("entry.newFileCreated"))
        assertTrue(journal.contains("lookup.record?.assetFileName == entry.assetFileName"))
        assertFalse(journal.contains("listFiles()"))
        assertTrue(registry.contains("GeneratedImageRegistrationJournal.begin"))
        assertTrue(registry.contains("GeneratedImageRegistrationJournal.markFileReady"))
        assertTrue(registry.contains("GeneratedImageRegistrationJournal.complete"))
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
