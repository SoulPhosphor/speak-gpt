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

import java.util.PriorityQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The one path to the embedding model (conversation_recall_plan.md G.5a
 * rule 6). The model reads one text at a time, so every read waits here for
 * its turn:
 *
 * - at most one read runs at once;
 * - a [Priority.LIVE] read (the message being sent, or anything else the
 *   user is waiting on) goes ahead of every waiting [Priority.BACKGROUND]
 *   read; reads of the same priority keep their arrival order;
 * - a live read therefore waits for at most the one read already running.
 *   When that running read is background work and the model can stop it
 *   ([ReadControl.whileRunning]), the queue asks it to stop. The stopped
 *   background read throws [ReadStoppedException], goes back into the queue
 *   ahead of later background work, and is read again after the live read.
 *
 * The work runs on the caller's own thread; the queue only decides the
 * order. A read must not call [run] again from inside its own work. Pure
 * Kotlin, unit tested (EmbeddingQueueTest).
 */
class EmbeddingQueue {

    enum class Priority { LIVE, BACKGROUND }

    /** Thrown by a model read that stopped because the queue asked it to. */
    class ReadStoppedException(cause: Throwable? = null) :
        RuntimeException("embedding read stopped for a live request", cause)

    /**
     * Lets the queue stop one running read. A model that can stop a read
     * wraps the read in [whileRunning]; a model that cannot simply ignores
     * the control, and a live request then waits for the read to finish.
     */
    class ReadControl internal constructor() {
        @Volatile var stopRequested: Boolean = false
            private set
        private var stopper: (() -> Unit)? = null

        /** Runs [read] while [stop] is available to the queue. If a stop was
         *  already requested, [stop] is called before [read] starts. */
        fun <T> whileRunning(stop: () -> Unit, read: () -> T): T {
            synchronized(this) {
                stopper = stop
                if (stopRequested) stop()
            }
            try {
                return read()
            } finally {
                synchronized(this) { stopper = null }
            }
        }

        internal fun requestStop() {
            synchronized(this) {
                stopRequested = true
                stopper?.invoke()
            }
        }
    }

    private class Ticket(val priority: Priority, val order: Long)

    private val lock = ReentrantLock()
    private val turnChanged = lock.newCondition()
    private val waiting = PriorityQueue<Ticket>(
        compareBy<Ticket>({ it.priority.ordinal }, { it.order })
    )
    private var running: Ticket? = null
    private var runningControl: ReadControl? = null
    private var nextOrder = 0L

    /**
     * Waits for this read's turn, then runs [work] on the calling thread.
     * Returns its result or rethrows its failure. A background read stopped
     * for a live request is retried here automatically, keeping its place
     * ahead of later background reads.
     */
    fun <T> run(priority: Priority, work: (ReadControl) -> T): T {
        val ticket = lock.withLock { Ticket(priority, nextOrder++) }
        var alreadyQueued = false
        while (true) {
            val control = awaitTurn(ticket, alreadyQueued)
            var requeue = false
            try {
                return work(control)
            } catch (e: ReadStoppedException) {
                // Only a stop the queue asked for is retried; anything else is
                // a real failure for the caller.
                if (!control.stopRequested || priority != Priority.BACKGROUND) throw e
                requeue = true
            } finally {
                // A stopped read goes back into the queue, with its original
                // order, in the same step that ends its turn, so no later
                // background read can take its place in between.
                finishTurn(ticket, requeue)
                alreadyQueued = requeue
            }
            afterStoppedForTest?.invoke()
        }
    }

    /** Test seam: runs on a stopped read's thread after its turn ends and
     *  before it waits again. Null outside tests. */
    @Volatile internal var afterStoppedForTest: (() -> Unit)? = null

    /** True while a read is running. For tests and measurements. */
    fun isBusy(): Boolean = lock.withLock { running != null }

    /** Reads waiting for their turn. For tests and measurements. */
    fun waitingCount(): Int = lock.withLock { waiting.size }

    private fun awaitTurn(ticket: Ticket, alreadyQueued: Boolean): ReadControl {
        var toStop: ReadControl? = null
        val control: ReadControl
        lock.lock()
        try {
            if (!alreadyQueued) waiting.add(ticket)
            val current = running
            if (ticket.priority == Priority.LIVE && current != null &&
                current.priority == Priority.BACKGROUND
            ) {
                toStop = runningControl
            }
            turnChanged.signalAll()
        } finally {
            lock.unlock()
        }
        // Outside the queue's lock: the model's stop call is foreign code.
        toStop?.requestStop()

        lock.lock()
        try {
            try {
                while (running != null || waiting.peek() !== ticket) turnChanged.await()
            } catch (e: InterruptedException) {
                waiting.remove(ticket)
                turnChanged.signalAll()
                throw e
            }
            waiting.poll()
            control = ReadControl()
            running = ticket
            runningControl = control
        } finally {
            lock.unlock()
        }
        return control
    }

    private fun finishTurn(ticket: Ticket, requeue: Boolean) {
        lock.withLock {
            if (running === ticket) {
                running = null
                runningControl = null
            }
            if (requeue) waiting.add(ticket)
            turnChanged.signalAll()
        }
    }
}
