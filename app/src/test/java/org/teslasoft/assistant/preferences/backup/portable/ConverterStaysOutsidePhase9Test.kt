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

    /** These files document what they stay away from by naming it, so every
     *  "must not appear" assertion reads code with the comments removed. */
    @Test
    fun theSeederNeverReachesForTheReplacementEngine() {
        val code = codeOnly(importer)
        assertFalse(code.contains("ChatRestoreManager"))
        assertFalse(code.contains("restoreFromArchive"))
        assertFalse(code.contains("quarantine"))
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
        val conversion = source("preferences/backup/portable/LegacyChatConversion.kt")
        for (file in listOf(importer, plan, conversion)) {
            val code = codeOnly(file)
            assertFalse(code.contains("Log."))
            assertFalse(code.contains("Logger"))
        }
    }

    /** The owner's export is the one irreplaceable artifact in this operation.
     *  The converter reads it and extracts elsewhere; the only thing it may
     *  delete on the source side is its own staging directory. */
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
        // This temporary path is deliberately unencrypted-only.
        assertFalse(body.contains("RecoveryCode.decode"))
        assertTrue(body.contains("EncryptedPackageUnsupported"))
    }

    @Test
    fun theConversionEngineCarriesNoWordingOfItsOwn() {
        val code = codeOnly(source("preferences/backup/portable/LegacyChatConversion.kt"))
        assertFalse(code.contains("R.string"))
        assertFalse(code.contains("Toast"))
    }

    @Test
    fun disposableDestinationIsClearedOnlyAfterTheChatArtifactValidates() {
        val conversion = source("preferences/backup/portable/LegacyChatConversion.kt")
        val body = conversion.substringAfter("fun convert(")
            .substringBefore("private fun clearDisposableDestination(")
        val parsed = body.indexOf("ChatLogicalImportPlan.parse(json)")
        val cleared = body.indexOf("clearDisposableDestination")
        val seeded = body.indexOf("seedEmptyInstallation")
        assertTrue(parsed >= 0)
        assertTrue(cleared > parsed)
        assertTrue(seeded > cleared)
    }

    /** The temporary control is reachable in every build but opens its own
     *  screen rather than embedding conversion into a permanent restore path. */
    @Test
    fun theConversionControlOpensASeparateTemporaryScreen() {
        val screen = source("ui/activities/MemoryBackupRestoreActivity.kt")
        val reveal = screen.substringAfter("btnLegacyConvert?.visibility = View.VISIBLE")
            .substringBefore("btnPortableExport?.setOnClickListener")
        assertTrue(reveal.contains("LegacyChatConverterActivity::class.java"))
        assertFalse(screen.contains("LegacyChatConversion.convert"))

        val layout = layoutSource()
        val button = layout.substringAfter("@+id/btn_legacy_convert")
            .substringBefore("/>")
        assertTrue(button.contains("android:visibility=\"gone\""))
    }

    /** The chosen export is read through the resolver and copied; the screen
     *  works on the copy and deletes it on every path. */
    @Test
    fun theScreenConvertsACopyAndNeverTheChosenFile() {
        val screen = source("ui/activities/LegacyChatConverterActivity.kt")
        val copy = screen.substringAfter("private fun copySource(")
            .substringBefore("private fun saveConvertedRecovery(")
        assertTrue(copy.contains("contentResolver.openInputStream(uri)"))
        assertTrue(copy.contains("cacheDir"))
        assertFalse(copy.contains("openOutputStream"))

        val run = screen.substringAfter("private fun convert(uri: Uri)")
            .substringBefore("private fun copySource(")
        assertTrue(run.contains("sourceCopy.delete()"))
    }

    @Test
    fun conversionBuildsARecoveryFileButNeverRestoresItAutomatically() {
        val screen = source("ui/activities/LegacyChatConverterActivity.kt")
        assertTrue(screen.contains("createVerifiedChatRecoveryArchive"))
        assertTrue(screen.contains("ActivityResultContracts.CreateDocument"))
        assertTrue(screen.contains("contentResolver.openOutputStream(uri"))
        assertTrue(screen.contains("MessageDigest.isEqual"))
        assertFalse(screen.contains("ChatRestoreManager"))
        assertFalse(screen.contains("restoreFromArchive"))
    }

    /** Every outcome the engine can return gets its own message. A cause the
     *  app knows is never collapsed into a generic failure. */
    @Test
    fun everyOutcomeAndRejectionReasonHasItsOwnMessage() {
        val screen = source("ui/activities/LegacyChatConverterActivity.kt")
        val mapping = screen.substringAfter("private fun conversionMessage(")
            .substringBefore("private fun rejectionReason(")
        val rejectionMapping = screen.substringAfter("private fun rejectionReason(")
            .substringBefore("private fun setBusy(")

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
            assertTrue("$name has no message", rejectionMapping.contains(name))
        }
    }

    /** Strips block and line comments so a "must not appear" check cannot be
     *  tripped by documentation that names the thing being avoided. */
    private fun codeOnly(source: String): String =
        source.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("(?<!:)//[^\\n]*"), "")

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
