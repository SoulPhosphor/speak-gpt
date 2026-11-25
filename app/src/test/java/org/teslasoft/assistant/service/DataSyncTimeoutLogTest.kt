package org.teslasoft.assistant.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The time-limit log lines of the data-sync services (Owner, 2026-11-25): a
 * timeout is named as Android's time limit, never as a cancellation or a
 * failure; identifiers trace the work; no chat name or content is written.
 */
class DataSyncTimeoutLogTest {

    @Test fun theSummarizerLineNamesTheTimeoutTheOperationsAndThatNothingWasCancelled() {
        assertEquals(
            "Summarizer Service: Android's background execution time limit was reached. The keep-alive service has stopped. " +
                "Operations running: compaction for chat c1. The operation was not explicitly cancelled, " +
                "but further execution depends on Android allowing the app to run.",
            DataSyncTimeoutLog.summarizer(listOf(DataSyncTimeoutLog.SummarizerOperation("c1", "compaction")))
        )
        val two = DataSyncTimeoutLog.summarizer(listOf(
            DataSyncTimeoutLog.SummarizerOperation("c1", "summarization"),
            DataSyncTimeoutLog.SummarizerOperation("c2", "chat outline")
        ))
        assertTrue(two.contains("Operations running: summarization for chat c1, chat outline for chat c2. These operations were not explicitly cancelled"))
        assertTrue(DataSyncTimeoutLog.summarizer(emptyList()).contains("Operations running: none recorded."))
    }

    @Test fun summarizerKindsReadAsWhatTheyAre() {
        assertEquals("compaction", DataSyncTimeoutLog.summarizerKind("COMPACTING"))
        assertEquals("chat outline", DataSyncTimeoutLog.summarizerKind("OUTLINING"))
        assertEquals("summarization", DataSyncTimeoutLog.summarizerKind("SUMMARIZING"))
    }

    @Test fun theImageGenerationLineListsTheJobsByTheirIds() {
        assertEquals(
            "Image Generation Service: Android's background execution time limit was reached. The keep-alive service has stopped. " +
                "Image generations running: image img-7 for chat c3. Image generation was not explicitly cancelled, " +
                "but further execution depends on Android allowing the app to run.",
            DataSyncTimeoutLog.imageGeneration(listOf(DataSyncTimeoutLog.ImageJob("c3", "img-7")))
        )
    }

    @Test fun theRestoreLineNamesTheOperationAndTheRecoveryAtTheNextLaunch() {
        assertEquals(
            "Restore Service: Android's background execution time limit was reached. The service has stopped. " +
                "Operation: unified backup restore. Restore progress may be interrupted. " +
                "An incomplete restore will be handled by the existing recovery mechanism at the next app launch.",
            DataSyncTimeoutLog.restore(DataSyncTimeoutLog.RestoreOperation.UNIFIED_RESTORE)
        )
        assertTrue(DataSyncTimeoutLog.restore(DataSyncTimeoutLog.RestoreOperation.CHAT_RESTORE)
            .contains("Operation: chat restore from a backup archive."))
    }

    @Test fun noLineCallsATimeoutACancellationOrAFailure() {
        val lines = listOf(
            DataSyncTimeoutLog.summarizer(listOf(DataSyncTimeoutLog.SummarizerOperation("c", "compaction"))),
            DataSyncTimeoutLog.imageGeneration(emptyList()),
            DataSyncTimeoutLog.restore(DataSyncTimeoutLog.RestoreOperation.CHAT_RESTORE)
        )
        for (line in lines) {
            assertTrue(line.contains("Android's background execution time limit was reached"))
            assertFalse(line, Regex("\\bfailed\\b|\\bwas cancelled\\b|\\bcompleted\\b", RegexOption.IGNORE_CASE).containsMatchIn(line))
        }
    }
}
