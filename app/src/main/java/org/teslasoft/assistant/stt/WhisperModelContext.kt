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

package org.teslasoft.assistant.stt

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the one loaded whisper.cpp model context and every rule for touching
 * its native handle. Kept free of Android and JNI (the native calls come in
 * through [NativeOps]) so the locking can be tested on the JVM.
 *
 * Two locks, always taken in this order when both are needed:
 *  - the context lock serializes loading, switching and releasing, so two
 *    loads can never leak a handle and two frees can never double-free;
 *  - the transcription lock serializes whisper_full. A context is freed ONLY
 *    while holding it, and a transcription reads the handle ONLY after
 *    acquiring it, so a handle can never be freed while a transcription uses
 *    it (no use-after-free) and a transcription never starts on a handle that
 *    was already freed.
 *
 * Freeing first raises the cooperative abort flag, so a long transcription
 * stops at its next decode step instead of holding the free back to its end.
 */
internal class WhisperModelContext(private val native: NativeOps) {

    interface NativeOps {
        /** True when the native library is available. */
        fun libraryReady(): Boolean
        /** Loads [path] for [modelId]; 0 on failure. */
        fun init(modelId: String, path: String): Long
        fun free(handle: Long)
        fun signalAbort()
        fun clearAbort()
    }

    /** One loaded context. [loadTicket] is the release-request count when
     *  its load began; a release requested later frees it, one requested
     *  earlier does not. */
    class Loaded internal constructor(
        val handle: Long,
        val modelId: String,
        internal val loadTicket: Long
    )

    @Volatile private var loaded: Loaded? = null
    private val releaseRequests = java.util.concurrent.atomic.AtomicLong(0)
    private val contextMutex = Mutex()
    private val transcribeMutex = Mutex()

    /** The context resident right now, or null. */
    fun current(): Loaded? = loaded

    fun isLoaded(modelId: String): Boolean = loaded?.modelId == modelId

    /**
     * Makes [modelId] the loaded context, reusing it when it already is.
     * A different loaded model is freed first. [resolvePath] returns the
     * model file's path, or null when it is unusable.
     */
    suspend fun ensure(modelId: String, resolvePath: () -> String?): Boolean =
        contextMutex.withLock {
            if (!native.libraryReady()) return@withLock false
            if (modelId.isEmpty()) return@withLock false
            if (loaded?.modelId == modelId) return@withLock true
            loaded?.let { freeLocked(it) }
            // Read before the load starts: a release requested from here on
            // must also cover this load, even though it is not loaded yet.
            val ticket = releaseRequests.get()
            val path = resolvePath() ?: return@withLock false
            val handle = try { native.init(modelId, path) } catch (_: Throwable) { 0L }
            if (handle == 0L) return@withLock false
            loaded = Loaded(handle, modelId, ticket)
            true
        }

    /**
     * Records a release request and returns its ticket. Call this at the
     * moment of the request; pass the ticket to [releaseLoadedBefore].
     */
    fun requestRelease(): Long = releaseRequests.incrementAndGet()

    /**
     * Frees the loaded context if its load began before release request
     * [ticket] — including a load that was still in progress when the
     * release was requested, since this waits for it to finish. A context
     * whose load began after the request (a newer model) is kept. Waits for
     * a running transcription to stop.
     */
    suspend fun releaseLoadedBefore(ticket: Long): Loaded? =
        contextMutex.withLock {
            val target = loaded ?: return@withLock null
            if (target.loadTicket >= ticket) return@withLock null
            if (freeLocked(target)) target else null
        }

    /** Frees whatever is loaded now. Returns the context it freed, or null. */
    suspend fun release(): Loaded? = releaseLoadedBefore(requestRelease())

    /** Caller holds the context lock. True when [target] was freed here. */
    private suspend fun freeLocked(target: Loaded): Boolean {
        native.signalAbort()
        return transcribeMutex.withLock {
            if (loaded !== target) return@withLock false
            loaded = null
            try { native.free(target.handle) } catch (_: Throwable) { /* best-effort */ }
            true
        }
    }

    /**
     * Runs [block] as the only transcription. Any earlier transcription is
     * asked to stop first. [block] receives the handle of [modelId] — read
     * under the transcription lock, so it stays valid for the whole block —
     * or 0 when that model is no longer loaded (released or switched away
     * after it was ensured).
     */
    suspend fun <T> withTranscription(modelId: String, block: (handle: Long) -> T): T {
        native.signalAbort()
        return transcribeMutex.withLock {
            // This run owns the lock, so clear the signal raised above.
            native.clearAbort()
            val current = loaded
            block(if (current != null && current.modelId == modelId) current.handle else 0L)
        }
    }
}
