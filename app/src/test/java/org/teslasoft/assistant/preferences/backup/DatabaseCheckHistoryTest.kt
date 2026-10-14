package org.teslasoft.assistant.preferences.backup

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DatabaseCheckHistoryTest {
    @Test fun databaseHistoryIncludesExactlyTheThreeCheckedDatabases() {
        assertEquals(listOf(BackupType.MEMORY, BackupType.LOREBOOK, BackupType.USER_IMAGE), DatabaseCheckHistory.databases)
        assertTrue(DatabaseCheckHistory.decode(null).isEmpty())
    }
    @Test fun laterPassReplacesThePreviousFailureAndKeepsOtherDatabases() {
        val previous = mapOf(BackupType.MEMORY to DatabaseCheckHistory.Record(100, DatabaseCheckHistory.Outcome.FAILED),
            BackupType.LOREBOOK to DatabaseCheckHistory.Record(100, DatabaseCheckHistory.Outcome.PASSED))
        val updated = DatabaseCheckHistory.merge(previous,
            mapOf(BackupType.MEMORY to DatabaseCheckHistory.Record(200, DatabaseCheckHistory.Outcome.PASSED)))
        assertEquals(DatabaseCheckHistory.Record(200, DatabaseCheckHistory.Outcome.PASSED), updated[BackupType.MEMORY])
        assertEquals(previous[BackupType.LOREBOOK], updated[BackupType.LOREBOOK])
        assertEquals(updated, DatabaseCheckHistory.decode(DatabaseCheckHistory.encode(updated)))
    }
    @Test fun unavailableResultDoesNotBecomeAFalseFailure() {
        val record = mapOf(BackupType.USER_IMAGE to DatabaseCheckHistory.Record(200, DatabaseCheckHistory.Outcome.UNAVAILABLE))
        assertEquals(record, DatabaseCheckHistory.decode(DatabaseCheckHistory.encode(record)))
    }
    @Test fun historyTimeUsesTheExistingFormatWithoutSeconds() {
        assertEquals("January 1, 1970 at 12:00 AM", BackupStatusFormatter.formatDateTime(12000, ZoneId.of("UTC")))
    }
}
