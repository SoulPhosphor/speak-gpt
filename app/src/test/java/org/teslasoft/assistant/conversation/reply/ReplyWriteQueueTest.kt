package org.teslasoft.assistant.conversation.reply

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ReplyWriteQueueTest {
    @Test fun aWorkerDelayedBeforeTheTransactionCannotOverwriteItsReplacement() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val saved = mutableListOf<String>()
            val queue = ReplyWriteQueue<String>(scope, 1) { value, wanted ->
                if (value == "partial") { entered.complete(Unit); release.await() }
                if (wanted()) saved.add(value)
                ReplyWriteQueue.Result.SAVED
            }
            queue.submit("partial")
            withTimeout(5000) { entered.await() }
            queue.submit("finished with usage")
            release.complete(Unit)
            withTimeout(5000) { queue.awaitSaved() }
            assertEquals(listOf("finished with usage"), saved)
        } finally { scope.cancel() }
    }

    @Test fun retiringWhileAWorkerWaitsWithdrawsTheWriteInsideTheTransaction() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val writes = AtomicInteger(0)
            val queue = ReplyWriteQueue<String>(scope, 1) { _, wanted ->
                entered.complete(Unit); release.await()
                if (wanted()) writes.incrementAndGet()
                finished.complete(Unit)
                ReplyWriteQueue.Result.SAVED
            }
            queue.submit("obsolete")
            withTimeout(5000) { entered.await() }
            queue.retire()
            release.complete(Unit)
            withTimeout(5000) { finished.await(); queue.awaitSaved() }
            assertEquals(0, writes.get())
        } finally { scope.cancel() }
    }

    @Test fun completionWaitsForAnActuallySuccessfulRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val first = CompletableDeferred<Unit>()
            val permit = CompletableDeferred<Unit>()
            val attempts = AtomicInteger(0)
            val queue = ReplyWriteQueue<String>(scope, 1) { _, _ ->
                if (attempts.incrementAndGet() == 1) { first.complete(Unit); ReplyWriteQueue.Result.RETRY }
                else { permit.await(); ReplyWriteQueue.Result.SAVED }
            }
            queue.submit("terminal")
            withTimeout(5000) { first.await() }
            val waiting = async { queue.awaitSaved() }
            yield()
            assertFalse(waiting.isCompleted)
            permit.complete(Unit)
            withTimeout(5000) { waiting.await() }
            assertTrue(attempts.get() >= 2)
        } finally { scope.cancel() }
    }

    @Test fun aNewCarrierAndAnEmptyReplyRemovalReachTheWriterTogether() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            data class Transaction(val usage: Int, val removeReply: Boolean)
            val entered = CompletableDeferred<Unit>()
            val permit = CompletableDeferred<Unit>()
            val saved = mutableListOf<Transaction>()
            val queue = ReplyWriteQueue<Transaction>(scope, 1) { tx, wanted ->
                if (!tx.removeReply) { entered.complete(Unit); permit.await() }
                if (wanted()) saved.add(tx)
                ReplyWriteQueue.Result.SAVED
            }
            queue.submit(Transaction(0, false))
            withTimeout(5000) { entered.await() }
            queue.submit(Transaction(23, true))
            permit.complete(Unit)
            withTimeout(5000) { queue.awaitSaved() }
            assertEquals(listOf(Transaction(23, true)), saved)
        } finally { scope.cancel() }
    }
}
