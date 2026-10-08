package org.teslasoft.assistant.util.summarizer

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Checks Android logger integration alongside executable cause/storage policy tests. */
class SummarizerDiagnosticWiringTest {
    private fun source(path: String): String {
        val root = if (File("src").exists()) File(".") else File("app")
        return File(root, "src/main/java/org/teslasoft/assistant/$path").readText()
    }

    @Test fun ownershipControlsExactlyOneDiagnosticChannel() {
        val controller = source("util/summarizer/SummarizerController.kt")
        assertFalse(controller.contains("providerAnswered"))
        assertTrue(controller.contains("outerHttpStatus = it.outerHttpStatus ?: httpStatus"))
        val routing = controller.substringAfter("private fun recordAppLogEntry").substringBefore("private fun recordStorageFailure")
        assertTrue(routing.contains("SummarizerDiagnostics.record("))
        assertTrue(routing.contains("logProviders = failureOwner != SummarizerDiagnostics.Owner.EXTERNAL || prefs.getLogChatFailures()"))
        assertTrue(routing.contains("local = {"))
        assertTrue(routing.contains("external = {"))
        assertTrue(routing.contains("SummarizerDiagnostics.localDetail(function, error, privateValues)"))
        assertTrue(routing.contains("Logger.logProviderFailure("))
        for (field in listOf("outerHttpStatus", "embeddedProviderStatus", "providerCode", "providerType", "providerErrorType", "contentFilterSide", "attemptId", "requestedRoutedProvider"))
            assertTrue(field, routing.contains("$field ="))
    }

    @Test fun storageRecordsActualExceptionAndOnlyRelevantApisAreWrapped() {
        val prefs = source("preferences/Preferences.kt")
        assertTrue(prefs.contains("summarizerStorageFailure = error"))
        assertTrue(prefs.contains("SummarizerDiagnostics.localDetail(operation, error, privateValues)"))
        assertTrue(
            prefs.substringAfter("fun commitSummarySections(json: String): Boolean")
                .substringBefore("fun getSummarizerCatchUpPending")
                .contains("summarizerStorageCommit")
        )
        assertTrue(prefs.substringAfter("fun commitManualCompaction(").substringBefore("fun compactionCheckpoint").contains("summarizerStorageCommit"))
    }
}
