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

    /** One loaded context. Identity matters: [release] frees only the
     *  context that was loaded when it was requested. */
    class Loaded internal constructor(val handle: Long, val modelId: String)

    @Volatile private var loaded: Loaded? = null
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
            val path = resolvePath() ?: return@withLock false
            val handle = try { native.init(modelId, path) } catch (_: Throwable) { 0L }
            if (handle == 0L) return@withLock false
            loaded = Loaded(handle, modelId)
            true
        }

    /**
     * Frees [expected] if it is still the loaded context; with null, frees
     * whatever is loaded. A context already replaced by a model switch was
     * freed by that switch, so a release requested earlier never frees the
     * model loaded after it. Waits for a running transcription to stop.
     */
    suspend fun release(expected: Loaded? = null) {
        contextMutex.withLock {
            val target = loaded ?: return@withLock
            if (expected != null && target !== expected) return@withLock
            freeLocked(target)
        }
    }

    /** Caller holds the context lock. */
    private suspend fun freeLocked(target: Loaded) {
        native.signalAbort()
        transcribeMutex.withLock {
            if (loaded !== target) return@withLock
            loaded = null
            try { native.free(target.handle) } catch (_: Throwable) { /* best-effort */ }
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
