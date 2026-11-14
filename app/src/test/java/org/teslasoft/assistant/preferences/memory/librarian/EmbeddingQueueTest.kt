/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.memory.librarian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingQueue.Priority.BACKGROUND
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingQueue.Priority.LIVE
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The single embedding queue (conversation_recall_plan.md G.5a rule 6):
 * one read at a time, live before every waiting background read, a live
 * read waits for at most one running read, and a background read stopped
 * for a live one is read again, never lost.
 */
class EmbeddingQueueTest {

    private val timeout = 5L

    private fun startThread(name: String, body: () -> Unit): Thread =
        Thread(body, name).apply { isDaemon = true; start() }

    private fun awaitWaiting(queue: EmbeddingQueue, count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout)
        while (queue.waitingCount() < count) {
            if (System.nanoTime() > deadline) fail("queue never reached $count waiting reads")
            Thread.sleep(1)
        }
    }

    /** Holds the queue with one running read until [release] opens. */
    private fun holdQueue(
        queue: EmbeddingQueue,
        priority: EmbeddingQueue.Priority,
        order: MutableList<String>,
        name: String,
        release: CountDownLatch
    ): Thread {
        val started = CountDownLatch(1)
        val thread = startThread(name) {
            queue.run(priority) {
                order.add(name)
                started.countDown()
                release.await(timeout, TimeUnit.SECONDS)
            }
        }
        assertTrue(started.await(timeout, TimeUnit.SECONDS))
        return thread
    }

    @Test
    fun liveReadGoesAheadOfEveryWaitingBackgroundRead() {
        val queue = EmbeddingQueue()
        val order = Collections.synchronizedList(ArrayList<String>())
        val release = CountDownLatch(1)
        val running = holdQueue(queue, BACKGROUND, order, "running", release)

        val b1 = startThread("b1") { queue.run(BACKGROUND) { order.add("b1") } }
        awaitWaiting(queue, 1)
        val b2 = startThread("b2") { queue.run(BACKGROUND) { order.add("b2") } }
        awaitWaiting(queue, 2)
        val live = startThread("live") { queue.run(LIVE) { order.add("live") } }
        awaitWaiting(queue, 3)

        release.countDown()
        for (t in listOf(running, b1, b2, live)) t.join(timeout * 1000)

        // The live read waited only for the one read already running.
        assertEquals(listOf("running", "live", "b1", "b2"), order)
        assertFalse(queue.isBusy())
    }

    @Test
    fun readsOfTheSamePriorityKeepArrivalOrder() {
        val queue = EmbeddingQueue()
        val order = Collections.synchronizedList(ArrayList<String>())
        val release = CountDownLatch(1)
        val running = holdQueue(queue, LIVE, order, "running", release)
        val threads = ArrayList<Thread>()
        for ((i, name) in listOf("l1", "l2", "l3").withIndex()) {
            threads += startThread(name) { queue.run(LIVE) { order.add(name) } }
            awaitWaiting(queue, i + 1)
        }
        release.countDown()
        running.join(timeout * 1000)
        threads.forEach { it.join(timeout * 1000) }
        assertEquals(listOf("running", "l1", "l2", "l3"), order)
    }

    @Test
    fun onlyOneReadRunsAtATime() {
        val queue = EmbeddingQueue()
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val threads = (0 until 8).map { i ->
            startThread("r$i") {
                repeat(20) {
                    queue.run(if (i % 2 == 0) LIVE else BACKGROUND) {
                        val now = active.incrementAndGet()
                        maxActive.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                        Thread.sleep(0, 200_000)
                        active.decrementAndGet()
                    }
                }
            }
        }
        threads.forEach { it.join(timeout * 4000) }
        assertEquals(1, maxActive.get())
        assertFalse(queue.isBusy())
    }

    /** A background read that can be stopped: the first attempt blocks until
     *  the queue stops it, later attempts finish at once. */
    private fun stoppableRead(
        name: String,
        order: MutableList<String>,
        attempts: AtomicInteger,
        started: CountDownLatch
    ): (EmbeddingQueue.ReadControl) -> String = { control ->
        val attempt = attempts.incrementAndGet()
        if (attempt == 1) {
            val stopped = CountDownLatch(1)
            control.whileRunning({ stopped.countDown() }) {
                order.add("$name-started")
                started.countDown()
                if (!stopped.await(timeout, TimeUnit.SECONDS)) fail("the read was never stopped")
                order.add("$name-stopped")
                throw EmbeddingQueue.ReadStoppedException()
            }
        } else {
            order.add("$name-done")
            "vector-$name"
        }
    }

    @Test
    fun liveReadStopsARunningBackgroundReadWhichIsReadAgainAfterIt() {
        val queue = EmbeddingQueue()
        val order = Collections.synchronizedList(ArrayList<String>())
        val attempts = AtomicInteger(0)
        val started = CountDownLatch(1)
        val result = AtomicReference<String>()
        val background = startThread("bg") {
            result.set(queue.run(BACKGROUND, stoppableRead("bg", order, attempts, started)))
        }
        assertTrue(started.await(timeout, TimeUnit.SECONDS))
        val live = startThread("live") { queue.run(LIVE) { order.add("live") } }
        live.join(timeout * 1000)
        background.join(timeout * 1000)

        assertEquals(listOf("bg-started", "bg-stopped", "live", "bg-done"), order)
        // No finished work is lost: the caller still receives its vector.
        assertEquals("vector-bg", result.get())
        assertEquals(2, attempts.get())
        assertFalse(queue.isBusy())
        assertEquals(0, queue.waitingCount())
    }

    @Test
    fun stoppedBackgroundReadStaysAheadOfLaterBackgroundReads() {
        stoppedReadKeepsItsPlace()
    }

    @Test
    fun stoppedReadKeepsItsPlaceWhileItsThreadIsBetweenTurns() {
        // Deterministic: the stopped read's thread is held exactly between the
        // end of its turn and its next wait, while the live read runs and
        // finishes. A later background read must still not take its place.
        val queue = EmbeddingQueue()
        val order = Collections.synchronizedList(ArrayList<String>())
        val attempts = AtomicInteger(0)
        val started = CountDownLatch(1)
        val liveDone = CountDownLatch(1)
        val laterStarted = CountDownLatch(1)
        var laterRanWhileHeld = false
        queue.afterStoppedForTest = {
            assertTrue(liveDone.await(timeout, TimeUnit.SECONDS))
            // The queue is now idle with the stopped read at its head. The
            // later read must not start, however long this thread is held.
            laterRanWhileHeld = laterStarted.await(300, TimeUnit.MILLISECONDS)
        }
        val first = startThread("b1") { queue.run(BACKGROUND, stoppableRead("b1", order, attempts, started)) }
        assertTrue(started.await(timeout, TimeUnit.SECONDS))
        val later = startThread("b2") { queue.run(BACKGROUND) { laterStarted.countDown(); order.add("b2") } }
        awaitWaiting(queue, 1)
        val live = startThread("live") { queue.run(LIVE) { order.add("live") }; liveDone.countDown() }
        for (t in listOf(live, first, later)) t.join(timeout * 1000)

        assertFalse("a later background read took the stopped read's place", laterRanWhileHeld)
        assertEquals(listOf("b1-started", "b1-stopped", "live", "b1-done", "b2"), order)
    }

    private fun stoppedReadKeepsItsPlace() {
        val queue = EmbeddingQueue()
        val order = Collections.synchronizedList(ArrayList<String>())
        val attempts = AtomicInteger(0)
        val started = CountDownLatch(1)
        val first = startThread("b1") { queue.run(BACKGROUND, stoppableRead("b1", order, attempts, started)) }
        assertTrue(started.await(timeout, TimeUnit.SECONDS))
        val second = startThread("b2") { queue.run(BACKGROUND) { order.add("b2") } }
        awaitWaiting(queue, 1)
        val live = startThread("live") { queue.run(LIVE) { order.add("live") } }
        for (t in listOf(live, first, second)) t.join(timeout * 1000)

        assertEquals(listOf("b1-started", "b1-stopped", "live", "b1-done", "b2"), order)
    }

    @Test
    fun aReadTheModelCannotStopIsWaitedForAndNotRepeated() {
        val queue = EmbeddingQueue()
        val order = Collections.synchronizedList(ArrayList<String>())
        val release = CountDownLatch(1)
        val runs = AtomicInteger(0)
        val started = CountDownLatch(1)
        val background = startThread("bg") {
            queue.run(BACKGROUND) {
                // No whileRunning: this model has no way to stop a read.
                runs.incrementAndGet()
                order.add("bg-started")
                started.countDown()
                release.await(timeout, TimeUnit.SECONDS)
                order.add("bg-done")
            }
        }
        assertTrue(started.await(timeout, TimeUnit.SECONDS))
        val live = startThread("live") { queue.run(LIVE) { order.add("live") } }
        awaitWaiting(queue, 1)
        release.countDown()
        live.join(timeout * 1000)
        background.join(timeout * 1000)
        assertEquals(listOf("bg-started", "bg-done", "live"), order)
        assertEquals(1, runs.get())
    }

    @Test
    fun aLiveReadIsNeverStoppedByAnotherLiveRead() {
        val queue = EmbeddingQueue()
        val stopCalls = AtomicInteger(0)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = startThread("l1") {
            queue.run(LIVE) { control ->
                control.whileRunning({ stopCalls.incrementAndGet() }) {
                    started.countDown()
                    release.await(timeout, TimeUnit.SECONDS)
                }
            }
        }
        assertTrue(started.await(timeout, TimeUnit.SECONDS))
        val second = startThread("l2") { queue.run(LIVE) { } }
        awaitWaiting(queue, 1)
        release.countDown()
        first.join(timeout * 1000)
        second.join(timeout * 1000)
        assertEquals(0, stopCalls.get())
    }

    @Test
    fun aStopRequestedBeforeTheReadStartsStopsItImmediately() {
        val control = EmbeddingQueue.ReadControl()
        control.requestStop()
        var stopped = false
        control.whileRunning({ stopped = true }) { }
        assertTrue(stopped)
        assertTrue(control.stopRequested)
    }

    @Test
    fun aStopTheQueueDidNotAskForIsAFailureForTheCaller() {
        val queue = EmbeddingQueue()
        var thrown: Throwable? = null
        try {
            queue.run(BACKGROUND) { throw EmbeddingQueue.ReadStoppedException() }
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue(thrown is EmbeddingQueue.ReadStoppedException)
        assertFalse(queue.isBusy())
    }

    @Test
    fun aFailedReadReleasesTheQueue() {
        val queue = EmbeddingQueue()
        try {
            queue.run(LIVE) { throw IllegalStateException("model failed") }
            fail("expected the failure to reach the caller")
        } catch (e: IllegalStateException) {
            assertEquals("model failed", e.message)
        }
        assertEquals("ok", queue.run(BACKGROUND) { "ok" })
    }

    @Test
    fun anInterruptedWaiterLeavesTheQueue() {
        val queue = EmbeddingQueue()
        val order = Collections.synchronizedList(ArrayList<String>())
        val release = CountDownLatch(1)
        val running = holdQueue(queue, BACKGROUND, order, "running", release)
        val interrupted = AtomicReference<Throwable>()
        val waiter = startThread("waiter") {
            try {
                queue.run(BACKGROUND) { order.add("waiter") }
            } catch (t: Throwable) {
                interrupted.set(t)
            }
        }
        awaitWaiting(queue, 1)
        waiter.interrupt()
        waiter.join(timeout * 1000)
        assertTrue(interrupted.get() is InterruptedException)
        assertEquals(0, queue.waitingCount())

        release.countDown()
        running.join(timeout * 1000)
        assertEquals("next", queue.run(LIVE) { "next" })
        assertEquals(listOf("running"), order)
    }
}
