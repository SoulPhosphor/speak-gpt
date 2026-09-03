package org.teslasoft.assistant.preferences.backup.portable

import java.io.File
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
