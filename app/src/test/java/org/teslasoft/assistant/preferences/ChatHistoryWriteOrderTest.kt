package org.teslasoft.assistant.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChatHistoryWriteOrderTest {
    private val chat = "chat-a"

    /** Stands in for stored history: the last list written per chat. */
    private class Storage {
        val stored = HashMap<String, List<String>>()
        var failuresLeft = 0
        val attempts = AtomicInteger()
        val writer = ChatHistoryWriteOrder.Writer { chatId, messages, _ ->
            attempts.incrementAndGet()
            synchronized(this) {
                if (failuresLeft > 0) { failuresLeft--; false }
                else { stored[chatId] = messages.map { it["message"].toString() }; true }
            }
        }
    }

    private fun rows(vararg text: String) = text.map { hashMapOf<String, Any>("message" to it) }

    private fun foreground(order: ChatHistoryWriteOrder, storage: Storage, vararg text: String): Boolean? {
        val seq = order.claim(chat)
        return order.writeIfNewest(chat, seq, { it }) { storage.writer.write(chat, rows(*text), false) }
    }

    @Test fun anOlderBackgroundSnapshotNeverReplacesANewerSave() {
        val storage = Storage()
        val order = ChatHistoryWriteOrder(scheduler = null)
        // The deletion's snapshot is claimed first but written after a newer save.
        val deletionSeq = order.claim(chat)
        assertEquals(true, foreground(order, storage, "kept", "new message"))
        order.saveInBackground(chat, deletionSeq, rows("kept"), false, storage.writer)
        assertEquals(listOf("kept", "new message"), storage.stored[chat])
        assertFalse(order.hasPending(chat))
    }

    @Test fun aDeletionPersistsWithoutAnyLaterMessage() {
        val storage = Storage()
        val order = ChatHistoryWriteOrder(scheduler = null)
        order.saveInBackground(chat, order.claim(chat), rows("kept"), false, storage.writer)
        assertEquals(listOf("kept"), storage.stored[chat])
        assertFalse(order.hasPending(chat))
    }

    @Test fun aFailedBackgroundSaveIsKeptRetriedAndServedToReaders() {
        val storage = Storage().apply { failuresLeft = 2 }
        val order = ChatHistoryWriteOrder(scheduler = null)
        storage.stored[chat] = listOf("kept", "deleted")
        order.saveInBackground(chat, order.claim(chat), rows("kept"), false, storage.writer)
        assertTrue(order.hasPending(chat))
        // A reader still sees the deletion even though storage refused it.
        val read = order.beforeRead(chat)
        assertEquals(listOf("kept"), read?.map { it["message"] })
        assertEquals(listOf("kept", "deleted"), storage.stored[chat])
        // The next attempt succeeds; nothing is kept any more.
        order.attemptNow(chat)
        assertEquals(listOf("kept"), storage.stored[chat])
        assertFalse(order.hasPending(chat))
        assertNull(order.beforeRead(chat))
    }

    @Test fun aNewerSuccessfulSaveEndsTheRetryWithoutWritingTheOlderSnapshot() {
        val storage = Storage().apply { failuresLeft = 1 }
        val order = ChatHistoryWriteOrder(scheduler = null)
        order.saveInBackground(chat, order.claim(chat), rows("kept"), false, storage.writer)
        assertTrue(order.hasPending(chat))
        assertEquals(true, foreground(order, storage, "kept", "new message"))
        assertFalse(order.hasPending(chat))
        val before = storage.attempts.get()
        order.attemptNow(chat)
        assertEquals(before, storage.attempts.get())
        assertEquals(listOf("kept", "new message"), storage.stored[chat])
    }

    @Test fun aNewerFailedSaveDoesNotCancelTheDeletionsRetry() {
        val storage = Storage().apply { failuresLeft = 2 }
        val order = ChatHistoryWriteOrder(scheduler = null)
        storage.stored[chat] = listOf("kept", "deleted")
        order.saveInBackground(chat, order.claim(chat), rows("kept"), false, storage.writer)
        assertEquals(false, foreground(order, storage, "kept", "new message"))
        order.attemptNow(chat)
        assertEquals(listOf("kept"), storage.stored[chat])
        // The newer save then lands after it, never before it.
        assertEquals(true, foreground(order, storage, "kept", "new message"))
        assertEquals(listOf("kept", "new message"), storage.stored[chat])
    }

    @Test fun deletingTheChatDropsAKeptSnapshot() {
        val storage = Storage().apply { failuresLeft = 1 }
        val order = ChatHistoryWriteOrder(scheduler = null)
        order.saveInBackground(chat, order.claim(chat), rows("kept"), false, storage.writer)
        order.discard(listOf(chat))
        assertFalse(order.hasPending(chat))
        order.attemptNow(chat)
        assertNull(storage.stored[chat])
    }

    @Test fun aRenamedChatCarriesItsKeptSnapshot() {
        val storage = Storage().apply { failuresLeft = 1 }
        val order = ChatHistoryWriteOrder(scheduler = null)
        order.saveInBackground(chat, order.claim(chat), rows("kept"), false, storage.writer)
        order.rename(chat, "chat-b")
        assertFalse(order.hasPending(chat))
        assertEquals(listOf("kept"), storage.stored["chat-b"])
    }

    @Test fun retriesRunOnTheSaveThreadUntilTheyLand() {
        val storage = Storage().apply { failuresLeft = 3 }
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val order = ChatHistoryWriteOrder(scheduler) { 1L }
            order.saveInBackground(chat, order.claim(chat), rows("kept"), false, storage.writer)
            val deadline = System.currentTimeMillis() + 5_000
            while (order.hasPending(chat) && System.currentTimeMillis() < deadline) Thread.sleep(5)
            assertFalse(order.hasPending(chat))
            assertEquals(listOf("kept"), storage.stored[chat])
            assertEquals(4, storage.attempts.get())
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test fun aForegroundSaveWaitsForARunningBackgroundWriteAndLandsAfterIt() {
        val storage = Storage()
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slowWriter = ChatHistoryWriteOrder.Writer { chatId, messages, bookmark ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            storage.writer.write(chatId, messages, bookmark)
        }
        try {
            val order = ChatHistoryWriteOrder(scheduler)
            order.saveInBackground(chat, order.claim(chat), rows("kept"), false, slowWriter)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val newer = order.claim(chat)
            val foregroundDone = CountDownLatch(1)
            Thread {
                order.writeIfNewest(chat, newer, { it }) { storage.writer.write(chat, rows("kept", "new message"), false) }
                foregroundDone.countDown()
            }.start()
            release.countDown()
            assertTrue(foregroundDone.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("kept", "new message"), storage.stored[chat])
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test fun snapshotsAreIndependentOfLaterEditsIncludingNestedValues() {
        val nested = arrayListOf<Any>("a")
        val row = hashMapOf<String, Any>("message" to "m", "list" to nested, "map" to hashMapOf("k" to "v"))
        val snapshot = ChatHistoryWriteOrder.snapshotOf(listOf(row))
        row["message"] = "changed"
        nested.add("b")
        @Suppress("UNCHECKED_CAST")
        (row["map"] as HashMap<String, String>)["k"] = "changed"
        assertEquals("m", snapshot.single()["message"])
        assertEquals(listOf("a"), snapshot.single()["list"])
        assertEquals(mapOf("k" to "v"), snapshot.single()["map"])
        assertNotSame(row, snapshot.single())
    }

    @Test fun retryDelaysGrowAndAreCapped() {
        assertEquals(250L, ChatHistoryWriteOrder.defaultRetryDelayMs(1))
        assertEquals(500L, ChatHistoryWriteOrder.defaultRetryDelayMs(2))
        assertEquals(30_000L, ChatHistoryWriteOrder.defaultRetryDelayMs(50))
    }
}
