package org.teslasoft.assistant.preferences.backup.portable

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8.6.4 forbids the owner-only conversion lane from becoming a second
 * restore engine. What keeps it out is structural, so it is asserted
 * structurally: the seeder refuses a destination that already holds chats,
 * writes the chat list last, and never touches the replacement machinery.
 */
class ConverterStaysOutsidePhase9Test {

    private val importer =
        source("preferences/backup/portable/ChatLogicalImporter.kt")

    @Test
    fun theSeederNeverReachesForTheReplacementEngine() {
        assertFalse(importer.contains("ChatRestoreManager"))
        assertFalse(importer.contains("restoreFromArchive"))
        assertFalse(importer.contains("quarantine"))
    }

    @Test
    fun aDestinationThatAlreadyHoldsChatsIsRefused() {
        val body = importer.substringAfter("fun seedEmptyInstallation(")
            .substringBefore("private fun writeSettings(")
        assertTrue(body.contains("RefusalReason.DESTINATION_NOT_EMPTY"))
        assertTrue(body.contains("existing.chats.isNotEmpty()"))
        // An unreadable list is not evidence of an empty one.
        assertTrue(body.contains("RefusalReason.DESTINATION_UNREADABLE"))
        assertTrue(body.contains("ChatStorageHealth.isAuthoritative"))
        assertTrue(
            body.indexOf("RefusalReason.DESTINATION_UNREADABLE") <
                body.indexOf("RefusalReason.DESTINATION_NOT_EMPTY")
        )
    }

    /** The chat list is what makes chats exist, so committing it last is what
     *  makes an interrupted run leave a still-empty, still-retryable target. */
    @Test
    fun theChatListIsCommittedAfterEveryHistoryAndSettingsFile() {
        val body = importer.substringAfter("fun seedEmptyInstallation(")
            .substringBefore("private fun writeSettings(")
        val history = body.indexOf("\"chat_\${chat.chatId}\"")
        val settings = body.indexOf("writeSettings(app, chat)")
        val list = body.indexOf("\"chat_list\"", history)
        assertTrue(settings in 0 until history)
        assertTrue(history in 0 until list)
    }

    /** Structural counts and stable ids only — the report is allowed nowhere
     *  near message text, titles, settings values or keys. */
    @Test
    fun theReportCarriesNoPrivatePayload() {
        val report = importer.substringAfter("data class Report(")
            .substringBefore("enum class RefusalReason")
        assertFalse(report.contains("messagesJson"))
        assertFalse(report.contains("listRow"))
        assertFalse(report.contains("name"))
        assertFalse(report.contains("value"))
    }

    /** The owner's logging ruling: this lane adds none. */
    @Test
    fun theConversionLaneAddsNoLogging() {
        val plan = source("preferences/backup/portable/ChatLogicalImportPlan.kt")
        for (file in listOf(importer, plan)) {
            assertFalse(file.contains("Log."))
            assertFalse(file.contains("Logger"))
        }
    }

    /** The owner's export is the one irreplaceable artifact in this operation.
     *  The converter reads it and extracts elsewhere; the only thing it may
     *  delete is its own staging directory. */
    @Test
    fun theOriginalExportIsNeverWrittenToOrDeleted() {
        val conversion = source("preferences/backup/portable/LegacyChatConversion.kt")
        val body = conversion.substringAfter("fun convert(")
        assertFalse(body.contains("packageFile.delete()"))
        assertFalse(body.contains("packageFile.writeText"))
        assertFalse(body.contains("packageFile.writeBytes"))
        assertFalse(body.contains("packageFile.outputStream"))
        // Staging is the only deletion, and it always happens.
        assertTrue(body.contains("PortableStaging.delete(staging)"))
        assertTrue(body.contains("finally"))
        // The decoded Recovery Code secret never outlives the call.
        assertTrue(body.contains("PackageCrypto.wipe(secret)"))
    }

    @Test
    fun theConversionEngineCarriesNoWordingOfItsOwn() {
        val conversion = source("preferences/backup/portable/LegacyChatConversion.kt")
        assertFalse(conversion.contains("R.string"))
        assertFalse(conversion.contains("Toast"))
        assertFalse(conversion.contains("Log."))
    }

    /** The converter may seed only the disposable Beta, so its control exists
     *  only there. The layout ships it hidden; the Beta build type is what
     *  reveals it. */
    @Test
    fun theConversionControlExistsOnlyInTheBeta() {
        val screen = source("ui/activities/MemoryBackupRestoreActivity.kt")
        val reveal = screen.substringAfter("BuildConfig.BUILD_TYPE == \"beta\"")
            .substringBefore("btnPortableExport?.setOnClickListener")
        assertTrue(reveal.contains("btnLegacyConvert?.visibility = View.VISIBLE"))
        assertTrue(reveal.contains("showLegacyConvertIntro()"))
        // The only place the control is shown is inside that guard.
        assertEquals(
            1,
            Regex("btnLegacyConvert\\?\\.visibility").findAll(screen).count()
        )

        val layout = layoutSource()
        val button = layout.substringAfter("@+id/btn_legacy_convert")
            .substringBefore("/>")
        assertTrue(button.contains("android:visibility=\"gone\""))
    }

    /** The chosen export is read through the resolver and copied; the screen
     *  works on the copy and deletes it on every path. */
    @Test
    fun theScreenConvertsACopyAndNeverTheChosenFile() {
        val screen = source("ui/activities/MemoryBackupRestoreActivity.kt")
        val copy = screen.substringAfter("private fun copyForLegacyConversion(")
            .substringBefore("private fun runLegacyConversion(")
        assertTrue(copy.contains("contentResolver.openInputStream(uri)"))
        assertTrue(copy.contains("cacheDir"))
        assertFalse(copy.contains("openOutputStream"))

        val run = screen.substringAfter("private fun runLegacyConversion(")
            .substringBefore("private fun promptLegacyRecoveryCode(")
        assertTrue(run.contains("copy.delete()"))

        val prompt = screen.substringAfter("private fun promptLegacyRecoveryCode(")
            .substringBefore("private fun legacyConversionMessage(")
        // Cancelling, dismissing, or a finishing screen must not strand it.
        assertEquals(3, Regex("copy\\.delete\\(\\)").findAll(prompt).count())
    }

    /** Every outcome the engine can return gets its own message. A cause the
     *  app knows is never collapsed into a generic failure. */
    @Test
    fun everyOutcomeAndRejectionReasonHasItsOwnMessage() {
        val screen = source("ui/activities/MemoryBackupRestoreActivity.kt")
        val mapping = screen.substringAfter("private fun legacyConversionMessage(")
            .substringBefore("private fun setPortableStatusText(")

        val outcomes = source("preferences/backup/portable/LegacyChatConversion.kt")
            .substringAfter("sealed class Outcome {")
            .substringBefore("\n    }\n")
        val declared = Regex("(?:object|data class) (\\w+)")
            .findAll(outcomes).map { it.groupValues[1] }.toList()
        assertEquals(9, declared.size)
        for (name in declared) {
            assertTrue("$name has no message", mapping.contains("Outcome.$name"))
        }

        val reasons = source("preferences/backup/portable/ChatLogicalImportPlan.kt")
            .substringAfter("enum class Reason {")
            .substringBefore("}")
        val reasonNames = Regex("^\\s+([A-Z_]+),?$", RegexOption.MULTILINE)
            .findAll(reasons).map { it.groupValues[1] }.toList()
        assertEquals(7, reasonNames.size)
        for (name in reasonNames) {
            assertTrue("$name has no message", mapping.contains(name))
        }
    }

    private fun layoutSource(): String {
        val candidates = listOf(
            File("src/main/res/layout/activity_memory_backup_restore.xml"),
            File("app/src/main/res/layout/activity_memory_backup_restore.xml")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Missing layout")
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/org/teslasoft/assistant/$relative"),
            File("app/src/main/java/org/teslasoft/assistant/$relative"),
            File(System.getProperty("user.dir"), "app/src/main/java/org/teslasoft/assistant/$relative")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Missing production source: $relative")
    }
}
