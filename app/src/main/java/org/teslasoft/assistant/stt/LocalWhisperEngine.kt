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

        // Energy-based voice-activity-detection tuning. A 16-bit PCM frame's
        // RMS runs 0..32767. Speech is "clearly above the adaptive noise
        // floor" — MIN_SPEECH_RMS is an absolute floor so dead-quiet rooms
        // (tiny noise floor) don't trip on faint fluctuations. These are the
        // numbers most likely to need on-device tuning.
        private const val MIN_SPEECH_RMS = 600.0
        private const val SPEECH_FLOOR_FACTOR = 2.5

        // On-device Whisper output filter. Whisper emits non-speech annotations
        // for ambient sound — "[Music]", "(applause)", "[wind blowing]",
        // "[coughs]", and ♪ glyphs for music — which the user (a) didn't say
        // and (b) doesn't want sent as a chat message. Strip them by default,
        // but keep expressive vocal sounds that carry intent: laughter,
        // sighs, gasps, sobs/crying. Cloud Whisper output is not touched.
        private val NON_SPEECH_TOKEN = Regex("""[\[(]([^\])]+)[\])]""")
        private val MUSIC_GLYPHS = Regex("[♪♫♬♩]")
        private val EXPRESSIVE_KEYWORDS = listOf(
            "laugh", "chuckl", "gigg", "sigh", "gasp", "sob", "cry"
        )

        private fun filterNonSpeechMarkers(text: String): String {
            val withoutGlyphs = text.replace(MUSIC_GLYPHS, "")
            val filtered = NON_SPEECH_TOKEN.replace(withoutGlyphs) { m ->
                val inner = m.groupValues[1].lowercase()
                if (EXPRESSIVE_KEYWORDS.any { inner.contains(it) }) m.value else ""
            }
            return filtered.replace(Regex("\\s+"), " ").trim()
        }

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

    // Serializes the actual whisper_full call. A whisper_context is NOT
    // thread-safe: running two transcriptions on the same loaded model at
    // once corrupts native memory and crashes the process with no Java
    // exception (the app just vanishes back to the previous app). The UI's
    // "cancel" only stops the coroutine awaiting the result — the native
    // call keeps running — so without this lock a user who gives up and
    // re-records would kick off a second concurrent whisper_full.
    private val transcribeMutex = Mutex()

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    @Volatile private var isCapturing: Boolean = false

    // Voice-activity-detection state for hands-free capture. Null config =>
    // plain push-to-talk (caller decides when to stop), matching the
    // original behaviour. Callbacks fire on the capture (IO) thread; the UI
    // caller is expected to marshal back to the main thread.
    @Volatile private var vadConfig: VadConfig? = null
    private var onVadEndOfTurn: (() -> Unit)? = null
    private var onVadNoSpeech: (() -> Unit)? = null

    // Snapshot of the detector's stats taken when a no-speech timeout fires, so
    // the UI can show the user why WebRTC heard nothing. Read on the main thread.
    @Volatile private var lastVadDiagnostics: String = ""

    /** Detector stats from the most recent no-speech timeout (may be empty). */
    fun lastVadDiagnostics(): String = lastVadDiagnostics

    /**
     * Voice-activity-detection config for hands-free capture. Recreates the
     * end-of-speech behaviour the platform SpeechRecognizer gives Google STT
     * (which whisper.cpp lacks): the capture loop owns the [silenceMs] /
     * [noSpeechMs] timers and fires [onEndOfTurn] once the user has spoken
     * and then gone quiet for the silence window, or [onNoSpeechTimeout] if
     * they never start. [method] selects which detector answers the per-frame
     * "is this speech?" question (see [VadMethods]); the timer behaviour is
     * identical across methods. Each callback fires at most once per recording.
     * [webRtcMode] is the WebRTC-only aggressiveness (libfvad 0..3); ignored by
     * the other detectors.
     */
    data class VadConfig(
        val silenceMs: Long,
        val noSpeechMs: Long,
        val method: String = VadMethods.DEFAULT,
        val webRtcMode: Int = VadMethods.WEBRTC_DEFAULT_MODE
    )

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
    fun startRecording(
        vad: VadConfig? = null,
        onEndOfTurn: (() -> Unit)? = null,
        onNoSpeechTimeout: (() -> Unit)? = null
    ): Boolean {
        if (isCapturing) return true
        synchronized(chunkLock) {
            capturedChunks.clear()
            capturedCount = 0
        }
        lastVadDiagnostics = ""
        vadConfig = vad
        onVadEndOfTurn = onEndOfTurn
        onVadNoSpeech = onNoSpeechTimeout

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
        // Build the detector once per recording, off the main thread. The
        // capture loop owns the silence/no-speech timers (so the user's
        // configured timings behave identically no matter which detector is
        // chosen); the detector only answers "is this frame speech?".
        val cfg = vadConfig
        val detector = cfg?.let { VadFactory.create(it.method, SAMPLE_RATE, it.webRtcMode) }
        detector?.reset()
        captureJob = CoroutineScope(Dispatchers.IO).launch {
            val readBuffer = ShortArray(readSize)
            val startedAt = SystemClock.elapsedRealtime()
            var speechStarted = false
            var lastVoiceAt = startedAt
            var vadFired = false
            try {
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

                        // Hands-free turn detection. Whisper has no end-of-speech
                        // signal, so we derive one: once the user has spoken and
                        // then stayed silent for the silence window, fire
                        // end-of-turn; if they never start within the no-speech
                        // window, fire that instead. Each fires once.
                        if (detector != null && !vadFired) {
                            val isSpeech = detector.accept(readBuffer, read)
                            // Refresh diagnostics every frame so a manual stop
                            // (mic-tap while "listens forever") still has the
                            // current voiced/total/peak counters to surface,
                            // not just a no-speech timeout.
                            lastVadDiagnostics = detector.diagnostics()
                            val now = SystemClock.elapsedRealtime()
                            if (isSpeech) {
                                speechStarted = true
                                lastVoiceAt = now
                            }
                            if (speechStarted && now - lastVoiceAt >= cfg.silenceMs) {
                                vadFired = true
                                onVadEndOfTurn?.invoke()
                            } else if (!speechStarted && now - startedAt >= cfg.noSpeechMs) {
                                vadFired = true
                                onVadNoSpeech?.invoke()
                            }
                        }
                    }
                }
            } finally {
                detector?.close()
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
        clearVad()
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
            // Ask any still-running transcription to bail. If a previous run
            // is hung (the model is slow and the user gave up), this lets us
            // take the lock promptly instead of blocking behind it — and,
            // critically, guarantees we never run two whisper_full calls on
            // the same context at once.
            LocalWhisperNative.signalAbort()
            transcribeMutex.withLock {
                // We now own the only transcription. Clear the abort flag so
                // this run isn't cut short by the signal we just raised.
                LocalWhisperNative.clearAbort()
                try {
                    val audioMs = samples.size * 1000L / SAMPLE_RATE
                    val startedAt = SystemClock.elapsedRealtime()
                    val text = LocalWhisperNative.transcribeNative(handle, samples, SAMPLE_RATE, language)
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    Log.i(TAG, "Transcribed ${audioMs}ms of audio in ${elapsed}ms")
                    val filtered = filterNonSpeechMarkers(text)
                    filtered.ifEmpty { null }
                } catch (t: Throwable) {
                    Log.w(TAG, "transcribeNative threw", t)
                    null
                }
            }
        }
    }

    /** Aborts the current recording (if any) without transcribing. */
    fun cancel() {
        // Stop a transcription that's already running natively, too — the
        // coroutine layer can't interrupt it on its own.
        LocalWhisperNative.signalAbort()
        isCapturing = false
        clearVad()
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

    /** Drops VAD config + callbacks so no stale turn callback can fire. */
    private fun clearVad() {
        vadConfig = null
        onVadEndOfTurn = null
        onVadNoSpeech = null
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

                // Swap out the old context if we're switching models. Freeing
                // a context out from under a running whisper_full is a
                // use-after-free, so abort any in-flight run and wait for it
                // to release the transcribe lock before we free. (No deadlock:
                // transcription only takes transcribeMutex after ensureContext
                // has already returned and released contextMutex.)
                if (nativeHandle != 0L) {
                    LocalWhisperNative.signalAbort()
                    transcribeMutex.withLock {
                        try { LocalWhisperNative.releaseContextNative(nativeHandle) } catch (_: Throwable) {}
                        nativeHandle = 0L
                        loadedModelId = ""
                    }
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
