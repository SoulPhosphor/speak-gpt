package org.teslasoft.assistant.imagegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ImageJobDeletionGuardTest {

    private class Job(var chatId: String)

    private val lock = Any()
    private val guard = ImageJobDeletionGuard<Job>(lock) { it.chatId }

    @Test fun jobForDeletedChatIsMarked() {
        val job = Job("deleted")
        guard.track(job)
        guard.markChatsDeleted(setOf("deleted"))
        assertTrue(guard.isChatDeleted(job))
    }

    @Test fun jobForAnotherChatIsNotMarked() {
        val kept = Job("kept")
        guard.track(kept)
        guard.markChatsDeleted(setOf("deleted"))
        assertFalse(guard.isChatDeleted(kept))
    }

    @Test fun failedDeletionNeverMarksAnything() {
        // A deletion that does not commit never calls markChatsDeleted, so the
        // job still delivers to its chat.
        val job = Job("chat")
        guard.track(job)
        assertFalse(guard.isChatDeleted(job))
        assertEquals(ImageJobDelivery.STORED_HISTORY, imageJobDelivery(guard.isChatDeleted(job), false))
    }

    @Test fun deletionWithoutAnActiveJobHoldsNothing() {
        guard.markChatsDeleted(setOf("no-job"))
        assertEquals(0, guard.trackedCount())
    }

    @Test fun finishedJobIsNoLongerHeld() {
        val job = Job("chat")
        guard.track(job)
        guard.markChatsDeleted(setOf("chat"))
        guard.untrack(job)
        assertEquals(0, guard.trackedCount())
        assertFalse(guard.isChatDeleted(job))
    }

    @Test fun reKeyedPlaceholderChatIsMatchedByItsCurrentId() {
        val job = Job("placeholder")
        guard.track(job)
        synchronized(lock) { job.chatId = "named" }
        guard.markChatsDeleted(setOf("placeholder"))
        assertFalse(guard.isChatDeleted(job))
        guard.markChatsDeleted(setOf("named"))
        assertTrue(guard.isChatDeleted(job))
    }

    @Test fun deletionCannotSlipBetweenTheCheckAndTheWrite() {
        // The stored-history write checks and writes while holding the lock;
        // a deletion arriving meanwhile must wait and mark afterwards.
        val job = Job("chat")
        guard.track(job)
        val writing = CountDownLatch(1)
        val marked = CountDownLatch(1)
        var wroteWhileDeleted = false
        val writer = Thread {
            synchronized(lock) {
                writing.countDown()
                val deleted = guard.isChatDeleted(job)
                Thread.sleep(50)
                if (!deleted) wroteWhileDeleted = guard.isChatDeleted(job)
            }
        }
        writer.start()
        assertTrue(writing.await(5, TimeUnit.SECONDS))
        Thread {
            guard.markChatsDeleted(setOf("chat"))
            marked.countDown()
        }.start()
        writer.join(5_000)
        assertTrue(marked.await(5, TimeUnit.SECONDS))
        assertFalse(wroteWhileDeleted)
        assertTrue(guard.isChatDeleted(job))
    }

    @Test fun deliveryRules() {
        assertEquals(ImageJobDelivery.DISCARD, imageJobDelivery(chatDeleted = true, screenAttached = true))
        assertEquals(ImageJobDelivery.DISCARD, imageJobDelivery(chatDeleted = true, screenAttached = false))
        assertEquals(ImageJobDelivery.SCREEN, imageJobDelivery(chatDeleted = false, screenAttached = true))
        assertEquals(ImageJobDelivery.STORED_HISTORY, imageJobDelivery(chatDeleted = false, screenAttached = false))
    }
}
