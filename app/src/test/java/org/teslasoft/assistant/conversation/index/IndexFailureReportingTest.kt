package org.teslasoft.assistant.conversation.index

import android.app.Application
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.preferences.memory.librarian.Librarian
import org.teslasoft.assistant.util.DiagnosticCause

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class IndexFailureReportingTest {
    @Test fun screenCausesAreBasedOnExceptionTypesRatherThanWordsInMessages() {
        val cases = listOf(
            SQLiteDatabaseLockedException("private detail") to IndexFailureReason.BUSY,
            SQLiteFullException("private detail") to IndexFailureReason.STORAGE_FULL,
            SQLiteDiskIOException("private detail") to IndexFailureReason.STORAGE_IO,
            IndexKeyUnavailableException(missing = false) to IndexFailureReason.KEY_UNAVAILABLE,
            SecurityException("private detail") to IndexFailureReason.ACCESS_DENIED,
            SQLiteReadOnlyDatabaseException("private detail") to IndexFailureReason.READ_ONLY,
            SQLiteCantOpenDatabaseException("private detail") to IndexFailureReason.CANNOT_OPEN,
            IllegalStateException("disk full, corrupt, permission denied") to IndexFailureReason.UNKNOWN
        )
        for ((failure, reason) in cases) {
            assertEquals(reason, IndexFailureReason.from(IllegalStateException("wrapper", failure)))
            assertFalse(IndexFailures.isDamage(failure))
        }
    }

    @Test fun aGenericOpenFailureDoesNotHideAKnownUnderlyingCause() {
        val failure = SQLiteCantOpenDatabaseException("cannot open").apply { initCause(SQLiteFullException("full")) }
        assertEquals(IndexFailureReason.STORAGE_FULL, IndexFailureReason.from(failure))
    }

    @Test fun failureToDeleteDataDoesNotPretendCleanupSucceeded() {
        var cleanupRan = false
        val failure = SQLiteDatabaseLockedException("busy")
        val result = IndexDeletion.finish(failure, { cleanupRan = true }, { cleanupRan = true })
        assertFalse(result.deleted)
        assertEquals(IndexDeleteStage.INDEX_DATA, result.stage)
        assertSame(failure, result.cause)
        assertFalse(cleanupRan)
    }

    @Test fun damagedFileCleanupFailureRetainsTheOriginalFailureAndNamesTheStep() {
        val damage = SQLiteDatabaseCorruptException("bad page")
        val cleanup = IOException("could not remove conversation_index.db")
        val result = IndexDeletion.finish(damage, { throw cleanup }, { fail("must not report cleanup complete") })
        assertEquals(IndexDeleteStage.DAMAGED_FILES, result.stage)
        assertFalse(result.deleted)
        assertTrue(DiagnosticCause.describe(result.cause!!).contains("suppressed: android.database.sqlite.SQLiteDatabaseCorruptException: bad page"))
        val entry = ConversationIndexErrorLog.deletionFailed(result)
        assertTrue(entry.contains("removing damaged index files"))
        assertTrue(entry.contains("java.io.IOException"))
        assertTrue(entry.contains("Indexing is off"))
    }

    @Test fun recoveryFileCleanupFailureIsNotReportedAsACompleteDeletion() {
        val result = IndexDeletion.finish(null, { fail("no damaged database") }, { throw IOException("recovery file remains") })
        assertFalse(result.deleted)
        assertEquals(IndexDeleteStage.RECOVERY_FILES, result.stage)
        assertTrue(ConversationIndexErrorLog.deletionFailed(result).contains("removing files kept by index recovery"))
    }

    @Test fun successfulDeletionIncludesSuccessfulCleanup() {
        val steps = mutableListOf<String>()
        val result = IndexDeletion.finish(SQLiteDatabaseCorruptException("bad page"), { steps.add("damaged") }, { steps.add("recovery") })
        assertTrue(result.deleted)
        assertEquals(listOf("damaged", "recovery"), steps)
        assertNull(result.cause)
    }

    @Test fun failuresAreDeduplicatedPerOperationAndResetOnlyWhenThatOperationSucceeds() {
        val entries = mutableListOf<String>()
        val reporter = ConversationIndexReporter({ _, text -> entries.add(text) }, { fail("not damage") })
        repeat(3) { reporter.operationFailed(IndexReadOperation.COUNTS, "database busy") }
        reporter.operationFailed(IndexReadOperation.CHECK, "database busy")
        assertEquals(2, entries.size)
        reporter.operationSucceeded(IndexReadOperation.CHECK)
        reporter.operationFailed(IndexReadOperation.COUNTS, "database busy")
        assertEquals(2, entries.size)
        reporter.operationSucceeded(IndexReadOperation.COUNTS)
        reporter.operationFailed(IndexReadOperation.COUNTS, "database busy")
        assertEquals(3, entries.size)
        assertTrue(entries[1].contains("does not confirm damage"))
    }

    @Test fun indexWideAccessFailuresReachTheLogWithoutStartingARebuild() {
        val entries = mutableListOf<String>()
        val reporter = ConversationIndexReporter({ _, text -> entries.add(text) }, { fail("not damage") })
        repeat(2) { reporter.indexFailed(SQLiteCantOpenDatabaseException("locked storage")) }
        assertEquals(1, entries.size)
        assertTrue(entries.single().contains("upkeep failed"))
        assertTrue(entries.single().contains("SQLiteCantOpenDatabaseException"))
    }

    @Test fun anUnreadableWaitingCountIsUnknownRatherThanZero() {
        val entries = mutableListOf<String>()
        val reporter = ConversationIndexReporter({ _, text -> entries.add(text) }, { fail("not damage") })
        reporter.vectorPass(
            PassageEmbeddingWorker.Outcome.Interrupted(PassageEmbeddingWorker.Counts(0, 0, 0), IOException("read failed")),
            2048
        ) { null }
        assertTrue(entries.single().contains("unknown number of passages"))
        assertFalse(entries.single().contains("for 0 passages"))
    }

    @Test fun diagnosticCausesRetainTypesAndNestedFailuresWithoutLooping() {
        val first = IOException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)
        val text = DiagnosticCause.describe(first)
        assertEquals("java.io.IOException: first; caused by java.lang.IllegalStateException: second", text)
    }

    @Test fun aThrowingSelfCheckClosesTheCandidateAndPreservesCleanupFailures() {
        var closed = false
        val text = Librarian.failedModelMessage("test-model", true, IOException("check threw")) {
            closed = true
            throw IllegalStateException("close failed")
        }
        assertTrue(closed)
        assertTrue(text.contains("failed its self-check"))
        assertFalse(text.contains("could not be loaded"))
        assertTrue(text.contains("java.io.IOException: check threw"))
        assertTrue(text.contains("suppressed: java.lang.IllegalStateException: close failed"))
    }

    @Test fun modelFailuresIdentifyTheModelOperationFallbackAndUnderlyingCause() {
        val text = Librarian.modelLoadFailureMessage("test-model", IllegalStateException("load", IOException("weights unreadable")))
        assertTrue(text.contains("could not be loaded"))
        assertTrue(text.contains("Saved-memory search is using keywords only"))
        assertTrue(text.contains("Model: test-model"))
        assertTrue(text.contains("java.io.IOException: weights unreadable"))
        assertFalse(text.contains("Conversation Recall"))
        val check = Librarian.selfCheckFailureMessage("test-model", "similarity check failed")
        assertTrue(check.contains("failed its self-check"))
        assertTrue(check.contains("similarity check failed"))
        val cleanup = Librarian.selfCheckFailureMessage("test-model", "wrong vector size", IOException("close failed"))
        assertTrue(cleanup.contains("wrong vector size"))
        assertTrue(cleanup.contains("Model cleanup also failed: java.io.IOException: close failed"))
    }
}
