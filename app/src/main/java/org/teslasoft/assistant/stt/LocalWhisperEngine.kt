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

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the audio-capture + whisper.cpp transcription path for "on-device
 * Whisper". Process-wide singleton because the loaded model context costs
 * ~150 MB (base.en) to ~1 GB (large-v3-turbo) of native RAM — we don't
 * want to reload it on every mic tap.
 *
 * Lifecycle:
 *   - [startRecording] opens AudioRecord at 16 kHz mono PCM-16, kicks off
 *     a coroutine that appends samples to an in-memory buffer.
 *   - [stopAndTranscribe] stops capture, ensures the context for the
 *     active model is loaded (lazy / swap-on-change), then runs
 *     whisper_full and returns the joined transcript.
 *   - [cancel] discards an in-flight recording without transcribing.
 *   - [release] frees the native context. Call from Activity.onDestroy
 *     of the last UI surface that may use STT, or just leave it for
 *     process death — it's not a resource leak that grows over time.
 */
class LocalWhisperEngine private constructor() {

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val TAG = "LocalWhisperEngine"

        @Volatile private var instance: LocalWhisperEngine? = null
        fun get(): LocalWhisperEngine {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: LocalWhisperEngine().also { instance = it }
            }
        }
    }

    @Volatile private var nativeHandle: Long = 0L
    @Volatile private var loadedModelId: String = ""

    // Serializes context init/release so a warm-up preload and a real
    // transcribe can't both call initContextNative for the same model and
    // leak a handle (or double-free on a model swap).
    private val contextMutex = Mutex()

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    @Volatile private var isCapturing: Boolean = false

    /** True iff the context for [activeModelId] is already resident in RAM. */
    fun isModelLoaded(activeModelId: String): Boolean =
        nativeHandle != 0L && loadedModelId == activeModelId

    /**
     * Loads the model context into native RAM ahead of time. Safe to call
     * repeatedly and concurrently with [stopAndTranscribe]; the heavy load
     * happens once. Intended to be fired the moment recording starts so the
     * (multi-second, for the larger models) load overlaps with the user
     * speaking instead of blocking after they stop.
     */
    suspend fun preload(context: Context, activeModelId: String): Boolean =
        ensureContext(context, activeModelId)

    // ArrayList<Short> would box every sample. Use a growable array of
    // short via mutableListOf<ShortArray>-of-chunks to stay primitive.
    private val capturedChunks = ArrayList<ShortArray>()
    private var capturedCount: Int = 0
    private val chunkLock = Any()

    /** True iff there's a recording session in progress. */
    fun isRecording(): Boolean = isCapturing

    /**
     * Starts mic capture. Returns false if AudioRecord couldn't be
     * initialized (e.g. mic permission missing, mic in use). Caller must
     * have already verified RECORD_AUDIO permission.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (isCapturing) return true
        synchronized(chunkLock) {
            capturedChunks.clear()
            capturedCount = 0
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.w(TAG, "AudioRecord.getMinBufferSize returned $minBuf")
            return false
        }
        val bufferBytes = minBuf * 4
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord ctor failed", t)
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord not initialized: state=${record.state}")
            record.release()
            return false
        }

        audioRecord = record
        try {
            record.startRecording()
        } catch (t: Throwable) {
            Log.w(TAG, "startRecording threw", t)
            record.release()
            audioRecord = null
            return false
        }

        isCapturing = true
        val readSize = bufferBytes / 2 // shorts per read
        captureJob = CoroutineScope(Dispatchers.IO).launch {
            val readBuffer = ShortArray(readSize)
            while (isActive && isCapturing) {
                val read = try {
                    record.read(readBuffer, 0, readBuffer.size)
                } catch (t: Throwable) {
                    Log.w(TAG, "AudioRecord.read threw", t)
                    break
                }
                if (read > 0) {
                    val copy = readBuffer.copyOf(read)
                    synchronized(chunkLock) {
                        capturedChunks.add(copy)
                        capturedCount += read
                    }
                }
            }
        }
        return true
    }

    /** Coarse phases reported to the UI so the user knows what they're waiting on. */
    enum class Phase { LOADING_MODEL, TRANSCRIBING }

    /**
     * Stops capture and transcribes the accumulated samples. Returns the
     * trimmed transcript on success, null on any failure (model load,
     * whisper_full error, empty audio, missing context).
     *
     * [onPhase] is invoked on the caller's dispatcher (Main when launched
     * from a UI coroutine) as the work moves from loading the model to
     * running transcription, so the caller can surface a status to the user.
     */
    suspend fun stopAndTranscribe(
        context: Context,
        activeModelId: String,
        language: String = "en",
        onPhase: ((Phase) -> Unit)? = null
    ): String? {
        if (!isCapturing && audioRecord == null) {
            return null
        }
        isCapturing = false
        try { captureJob?.cancelAndJoin() } catch (_: Throwable) {}
        captureJob = null

        try { audioRecord?.stop() } catch (_: Throwable) {}
        audioRecord?.release()
        audioRecord = null

        val samples = drainBuffer()
        if (samples.isEmpty()) return null

        if (!isModelLoaded(activeModelId)) onPhase?.invoke(Phase.LOADING_MODEL)
        if (!ensureContext(context, activeModelId)) return null
        val handle = nativeHandle
        if (handle == 0L) return null

        onPhase?.invoke(Phase.TRANSCRIBING)

        return withContext(Dispatchers.IO) {
            try {
                val audioMs = samples.size * 1000L / SAMPLE_RATE
                val startedAt = SystemClock.elapsedRealtime()
                val text = LocalWhisperNative.transcribeNative(handle, samples, SAMPLE_RATE, language)
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                Log.i(TAG, "Transcribed ${audioMs}ms of audio in ${elapsed}ms")
                val trimmed = text.trim()
                trimmed.ifEmpty { null }
            } catch (t: Throwable) {
                Log.w(TAG, "transcribeNative threw", t)
                null
            }
        }
    }

    /** Aborts the current recording (if any) without transcribing. */
    fun cancel() {
        isCapturing = false
        try { captureJob?.cancel() } catch (_: Throwable) {}
        captureJob = null
        try { audioRecord?.stop() } catch (_: Throwable) {}
        audioRecord?.release()
        audioRecord = null
        synchronized(chunkLock) {
            capturedChunks.clear()
            capturedCount = 0
        }
    }

    /**
     * Frees the loaded whisper context. Safe to call multiple times.
     * Subsequent transcribe calls will reload from disk.
     */
    fun release() {
        cancel()
        val handle = nativeHandle
        if (handle != 0L) {
            try { LocalWhisperNative.releaseContextNative(handle) } catch (_: Throwable) {}
            nativeHandle = 0L
            loadedModelId = ""
        }
    }

    private fun drainBuffer(): ShortArray {
        synchronized(chunkLock) {
            val out = ShortArray(capturedCount)
            var offset = 0
            for (chunk in capturedChunks) {
                System.arraycopy(chunk, 0, out, offset, chunk.size)
                offset += chunk.size
            }
            capturedChunks.clear()
            capturedCount = 0
            return out
        }
    }

    /**
     * Loads the model file for [activeModelId] into native memory if
     * needed. If a different model was previously loaded, releases it
     * first. Runs on IO; safe to await from a coroutine.
     */
    private suspend fun ensureContext(context: Context, activeModelId: String): Boolean =
        withContext(Dispatchers.IO) {
            contextMutex.withLock {
                if (!LocalWhisperNative.ensureLoaded()) return@withLock false
                if (activeModelId.isEmpty()) return@withLock false

                // Reuse existing context if the model hasn't changed.
                if (nativeHandle != 0L && loadedModelId == activeModelId) return@withLock true

                // Swap out the old context if we're switching models.
                if (nativeHandle != 0L) {
                    try { LocalWhisperNative.releaseContextNative(nativeHandle) } catch (_: Throwable) {}
                    nativeHandle = 0L
                    loadedModelId = ""
                }

                val model = LocalWhisperModels.byId(activeModelId) ?: return@withLock false
                val file = LocalWhisperStorage.fileFor(context, model)
                if (!file.exists() || file.length() == 0L) {
                    Log.w(TAG, "Model file missing for $activeModelId at ${file.absolutePath}")
                    return@withLock false
                }

                val startedAt = SystemClock.elapsedRealtime()
                val handle = try {
                    LocalWhisperNative.initContextNative(file.absolutePath)
                } catch (t: Throwable) {
                    Log.w(TAG, "initContextNative threw", t)
                    0L
                }
                if (handle == 0L) {
                    Log.w(TAG, "initContextNative returned 0 for ${file.absolutePath}")
                    return@withLock false
                }

                nativeHandle = handle
                loadedModelId = activeModelId
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                Log.i(TAG, "Loaded whisper model $activeModelId in ${elapsed}ms")
                true
            }
        }
}
