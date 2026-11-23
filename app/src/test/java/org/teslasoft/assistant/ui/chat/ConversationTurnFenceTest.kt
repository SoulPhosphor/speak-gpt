package org.teslasoft.assistant.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

class ConversationTurnFenceTest {
    @Test fun aReplyStartedBeforeRetireIsStaleOnlyInsideItsOwnCoroutine() = runBlocking {
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val fence = ConversationTurnFence()
            val gate = CompletableDeferred<Unit>()
            val seen = mutableListOf<Boolean>()
            val old = CoroutineScope(main + fence.element()).launch {
                seen += fence.isStale()
                gate.await()
                seen += fence.isStale()
                // Work the reply does on another thread is fenced too.
                seen += withContext(Dispatchers.IO) { fence.isStale() }
            }
            withContext(main) {
                fence.retire()
                // Code outside any reply (a tap, the deletion itself) is never stale.
                assertFalse(fence.isStale())
            }
            // A reply started after the deletion owns the conversation.
            val fresh = withContext(main) {
                CompletableDeferred<Boolean>().also { result ->
                    CoroutineScope(main + fence.element()).launch { result.complete(fence.isStale()) }
                }
            }.await()
            gate.complete(Unit)
            old.join()
            assertEquals(listOf(false, true, true), seen)
            assertFalse(fresh)
        } finally {
            main.close()
        }
    }

    @Test fun everyRetireFencesOffEarlierReplies() = runBlocking {
        val fence = ConversationTurnFence()
        val gate = CompletableDeferred<Unit>()
        var stale = false
        val job = CoroutineScope(Dispatchers.Default + fence.element()).launch {
            gate.await()
            stale = fence.isStale()
        }
        fence.retire()
        fence.retire()
        gate.complete(Unit)
        job.join()
        assertTrue(stale)
    }
}
