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

package org.teslasoft.assistant.imagegen

/**
 * Remembers which in-flight image jobs belong to a chat whose deletion has
 * committed, so the job's late result is never written back into that chat.
 *
 * Only jobs between [track] and [untrack] are held, so nothing outlives the
 * job itself. Every method synchronizes on [lock]; production passes the chat
 * list lock, which the stored-history write already holds. That makes "is
 * this chat deleted?" and "append the result" one atomic step relative to a
 * deletion marking the job — a deletion either marks the job before the write
 * (the write is skipped) or after it (the deletion's own cleanup then removes
 * what was written).
 *
 * Jobs are compared by identity: a re-keyed placeholder chat keeps the same
 * job object, and [chatIdOf] reads its current chat id.
 */
internal class ImageJobDeletionGuard<J : Any>(
    private val lock: Any,
    private val chatIdOf: (J) -> String
) {
    private val live = java.util.IdentityHashMap<J, Boolean>()

    fun track(job: J) = synchronized(lock) { live[job] = false }

    fun untrack(job: J) = synchronized(lock) { live.remove(job); Unit }

    /** Called once a deletion of [chatIds] has committed. */
    fun markChatsDeleted(chatIds: Set<String>) = synchronized(lock) {
        for (entry in live.entries) {
            if (chatIdOf(entry.key) in chatIds) entry.setValue(true)
        }
    }

    fun isChatDeleted(job: J): Boolean = synchronized(lock) { live[job] == true }

    /**
     * Runs [block] only while [job]'s chat is not deleted, holding the lock
     * for the whole block; returns null without running it otherwise. A
     * deletion therefore marks the job either after [block] finished — so
     * whatever [block] recorded already exists when the deletion's own
     * cleanup looks for it — or before it, and [block] never runs.
     */
    fun <T> unlessChatDeleted(job: J, block: () -> T): T? = synchronized(lock) {
        if (live[job] == true) null else block()
    }

    /** Number of jobs currently tracked. For tests. */
    fun trackedCount(): Int = synchronized(lock) { live.size }
}

/** Where a finished image job's single terminal state goes. */
internal enum class ImageJobDelivery { SCREEN, STORED_HISTORY, DISCARD }

/** A deleted chat receives nothing; otherwise the attached screen, or the
 *  stored history when no screen is attached. */
internal fun imageJobDelivery(chatDeleted: Boolean, screenAttached: Boolean): ImageJobDelivery =
    when {
        chatDeleted -> ImageJobDelivery.DISCARD
        screenAttached -> ImageJobDelivery.SCREEN
        else -> ImageJobDelivery.STORED_HISTORY
    }
