package org.teslasoft.assistant.conversation.index

import android.app.Application
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDiskIOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Step 11 (G.13a, Owner 2026-11-24): damage is told apart from failures that
 * pass; a passing failure removes nothing; recovery keeps every vector it can
 * read, sets the damaged file aside and removes it only once the new index
 * passed its check; and the Error Log gets each ongoing failure once, with
 * its cause and no content. The real files and SQLCipher are covered by
 * `ConversationIndexSettingsInstrumentedTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ConversationIndexRecoveryTest {

    private class Files(
        var key: IndexRecoveryFiles.Key = IndexRecoveryFiles.Key.PRESENT,
        var kept: KeptVectors = KeptVectors(emptyList(), null),
        var createFails: Exception? = null
    ) : IndexRecoveryFiles {
        val steps = ArrayList<String>()
        var created: List<KeptVector>? = null
        override fun keyState() = key
        override fun readKeptVectors(): KeptVectors { steps.add("read"); return kept }
        override fun setDamagedAside() { steps.add("aside") }
        override fun createWith(vectors: List<KeptVector>): Int {
            steps.add("create")
            createFails?.let { throw it }
            created = vectors
            return vectors.size
        }
        override fun discardDamaged() { steps.add("discard damaged") }
        override fun discardNew() { steps.add("discard new") }
    }

    private fun vector(n: Int) = KeptVector(VectorKey("f$n", "m", 2), ByteArray(8))

    // --- What counts as damage ------------------------------------------------

    @Test fun onlyACorruptFileOrAMissingKeyIsDamage() {
        assertTrue(IndexFailures.isDamage(SQLiteDatabaseCorruptException("file is not a database")))
        assertTrue(IndexFailures.isDamage(IllegalStateException("wrapped", SQLiteDatabaseCorruptException("bad page"))))
        assertTrue(IndexFailures.isDamage(IndexKeyUnavailableException(missing = true)))
        assertFalse(IndexFailures.isDamage(IndexKeyUnavailableException(missing = false)))
        assertFalse(IndexFailures.isDamage(SQLiteCantOpenDatabaseException("cannot open")))
        assertFalse(IndexFailures.isDamage(SQLiteDiskIOException("disk I/O")))
        assertFalse(IndexFailures.isDamage(IllegalStateException("disk full")))
    }

    // --- Recovery -------------------------------------------------------------

    @Test fun aKeyStoreThatCannotBeReadNowRemovesNothing() {
        val files = Files(key = IndexRecoveryFiles.Key.UNAVAILABLE)
        val result = IndexRecovery(files).recover()
        assertTrue(result is IndexRecovery.Result.Postponed)
        assertTrue(files.steps.isEmpty())
    }

    @Test fun aRecoveryKeepsEveryReadableVectorAndRemovesTheOldFileOnlyAfterTheNewOnePassed() {
        val files = Files(kept = KeptVectors(listOf(vector(1), vector(2)), null))
        val result = IndexRecovery(files).recover()
        assertEquals(IndexRecovery.Result.Rebuilt(retained = 2, notRead = null), result)
        assertEquals(listOf("read", "aside", "create", "discard damaged"), files.steps)
        assertEquals(2, files.created!!.size)
    }

    @Test fun aDamagedFileReadPartWayKeepsWhatWasReadAndSaysWhyTheRestWasNot() {
        val files = Files(kept = KeptVectors(listOf(vector(1)), SQLiteDatabaseCorruptException("bad page 7")))
        val result = IndexRecovery(files).recover() as IndexRecovery.Result.Rebuilt
        assertEquals(1, result.retained)
        assertTrue(result.notRead!!.contains("bad page 7"))
    }

    @Test fun aMissingKeyRebuildsWithNoVectorsAndRecordsWhy() {
        val files = Files(key = IndexRecoveryFiles.Key.ABSENT)
        val result = IndexRecovery(files).recover() as IndexRecovery.Result.Rebuilt
        assertEquals(0, result.retained)
        assertTrue(result.notRead!!.contains("key is missing"))
        assertFalse("read" in files.steps)
    }

    @Test fun anUnrecoverableIndexFailsAndLeavesNoHalfMadeDatabase() {
        val files = Files(createFails = IllegalStateException("the new index failed its check: page 3"))
        val result = IndexRecovery(files).recover()
        assertTrue(result is IndexRecovery.Result.Failed)
        assertEquals(listOf("read", "aside", "create", "discard new"), files.steps)
    }

    @Test fun recoveryWaitsForRunningWorkWhichSeesItAndStops() {
        val inside = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val sawRecovery = java.util.concurrent.atomic.AtomicBoolean(false)
        val workFinished = java.util.concurrent.atomic.AtomicBoolean(false)
        val work = Thread {
            ConversationIndexAccess.shared {
                inside.countDown()
                // A running pass checks this between vectors and stops.
                val deadline = System.currentTimeMillis() + 5_000
                while (!ConversationIndexAccess.recovering && System.currentTimeMillis() < deadline) Thread.sleep(5)
                sawRecovery.set(ConversationIndexAccess.recovering)
                release.await()
                workFinished.set(true)
            }
        }
        work.start()
        inside.await()
        val replacedWhileWorkRan = java.util.concurrent.atomic.AtomicBoolean(true)
        val recovery = Thread {
            ConversationIndexAccess.exclusive { replacedWhileWorkRan.set(!workFinished.get()) }
        }
        recovery.start()
        Thread.sleep(50)
        release.countDown()
        work.join(5_000)
        recovery.join(5_000)
        assertTrue(sawRecovery.get())
        assertFalse(replacedWhileWorkRan.get())
        assertFalse(ConversationIndexAccess.recovering)
    }

    // --- The Error Log ------------------------------------------------------------

    private val written = ArrayList<String>()
    private val damage = ArrayList<String>()
    private val reporter = ConversationIndexReporter({ _, message -> written.add(message) }, { damage.add(it) })

    @Test fun aChatFailureIsLoggedOnceWithItsCauseUntilTheChatIsIndexed() {
        val cause = IllegalArgumentException("speaker title must be one plain line")
        reporter.chatFailed("chat-7", cause)
        reporter.chatFailed("chat-7", cause)
        assertEquals(1, written.size)
        assertEquals(
            "Conversation indexing failed for chat chat-7: java.lang.IllegalArgumentException: speaker title must be " +
                "one plain line. Index remains incomplete. Retry: automatically when the chat next changes, or at the " +
                "next app start.",
            written.single()
        )
        reporter.chatIndexed("chat-7")
        reporter.chatFailed("chat-7", cause)
        assertEquals(2, written.size)
    }

    @Test fun anIndexThatCannotBeOpenedIsLoggedOnceForAllChats() {
        reporter.chatFailed("one", IndexKeyUnavailableException(missing = false))
        reporter.chatFailed("two", IndexKeyUnavailableException(missing = false))
        assertEquals(1, written.size)
        assertTrue(damage.isEmpty())
    }

    @Test fun damageStartsRecoveryInsteadOfAChatEntry() {
        reporter.chatFailed("one", SQLiteDatabaseCorruptException("database disk image is malformed"))
        reporter.indexFailed(IndexKeyUnavailableException(missing = true))
        assertTrue(written.isEmpty())
        assertEquals(2, damage.size)
    }

    @Test fun permanentlyFailedVectorsAreRecordedAsPermanentOnce() {
        val counts = PassageEmbeddingWorker.Counts(made = 3, failed = 2, skipped = 0)
        reporter.vectorPass(PassageEmbeddingWorker.Outcome.Finished(counts, 0), 2048) { 0 }
        val entry = written.single()
        assertTrue(entry.startsWith("Conversation search vector preparation failed for 2 passages:"))
        assertTrue(entry.contains("reading limit of 2048 tokens"))
        assertTrue(entry.contains("Retry: none; permanently failed."))
        reporter.vectorPass(PassageEmbeddingWorker.Outcome.Finished(PassageEmbeddingWorker.Counts(0, 0, 0), 0), 2048) { 0 }
        assertEquals(1, written.size)
    }

    @Test fun aStoppedVectorPassIsLoggedOncePerCauseAndAsWaiting() {
        val stopped = PassageEmbeddingWorker.Outcome.Interrupted(PassageEmbeddingWorker.Counts(0, 0, 0), IllegalStateException("ORT session failed"))
        reporter.vectorPass(stopped, 2048) { 12 }
        reporter.vectorPass(stopped, 2048) { 12 }
        val entry = written.single()
        assertTrue(entry.startsWith("Conversation search vector preparation failed for 12 passages: java.lang.IllegalStateException: ORT session failed."))
        assertTrue(entry.contains("they stay waiting"))
        assertTrue(entry.contains("Retry: automatically on the next indexing pass"))
        reporter.vectorPass(PassageEmbeddingWorker.Outcome.Finished(PassageEmbeddingWorker.Counts(1, 0, 0), 0), 2048) { 0 }
        reporter.vectorPass(stopped, 2048) { 3 }
        assertEquals(2, written.size)
    }

    @Test fun theModelEntryIsWrittenForANewConditionOnly() {
        reporter.modelUnavailable(newCondition = true, detail = "no embedding model is installed")
        reporter.modelUnavailable(newCondition = false, detail = "no embedding model is installed")
        assertEquals(
            listOf(
                "Conversation indexing paused because the on-device search model is unavailable: no embedding model is " +
                    "installed. Indexing will resume when the model is available."
            ),
            written
        )
    }

    @Test fun theRebuildEntriesReportMeasuredResults() {
        assertEquals(
            "Conversation index rebuild completed. 12 conversations queued for indexing. 340 existing vectors retained.",
            ConversationIndexErrorLog.rebuildCompleted(12, 340, null)
        )
        val none = ConversationIndexErrorLog.rebuildCompleted(1, 0, "net.zetetic: file is not a database")
        assertTrue(none.contains("1 conversation queued"))
        assertTrue(none.contains("0 existing vectors retained."))
        assertTrue(none.contains("file is not a database"))
        assertEquals(
            "Conversation index database problem detected: integrity check: page 4 is never used. Automatic recovery started.",
            ConversationIndexErrorLog.damageDetected("integrity check: page 4 is never used")
        )
        assertEquals(
            "Conversation index rebuild failed: java.io.IOException: no space. Index Conversations was disabled. " +
                "Recovery requires another indexing attempt.",
            ConversationIndexErrorLog.rebuildFailed("java.io.IOException: no space")
        )
    }

    @Test fun aCauseNamesTheExceptionTypesAlongTheChain() {
        val cause = ConversationIndexErrorLog.cause(IllegalStateException("outer", SQLiteDiskIOException("inner")))
        assertEquals(
            "java.lang.IllegalStateException: outer; caused by android.database.sqlite.SQLiteDiskIOException: inner",
            cause
        )
        assertNotNull(cause)
        assertNull(null)
    }
}
