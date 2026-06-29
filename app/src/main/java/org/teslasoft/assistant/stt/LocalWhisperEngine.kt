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
import android.media.AudioDeviceInfo
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
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.preferences.Preferences

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

        // Energy-based voice-activity-detection tuning lives in VadTuning
        // (user-editable in advanced voice settings); the engine only carries
        // it through to the detector and the diagnostics log lines.

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

        private fun rmsOfShort(buf: ShortArray, len: Int): Double {
            if (len <= 0) return 0.0
            var sum = 0.0
            for (i in 0 until len) { val s = buf[i].toDouble(); sum += s * s }
            return kotlin.math.sqrt(sum / len)
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
    @Volatile private var turnNumber = 0

    // Snapshot of the detector's stats taken when a no-speech timeout fires, so
    // the UI can show the user why WebRTC heard nothing. Read on the main thread.
    @Volatile private var lastVadDiagnostics: String = ""

    /** Detector stats from the most recent no-speech timeout (may be empty). */
    fun lastVadDiagnostics(): String = lastVadDiagnostics

    // Snapshot of microphone input-health stats taken when a turn ends (the
    // optional "Audio Health" diagnostic). Independent of the VAD snapshot so
    // the two can be reported together or separately. Read on the main thread.
    @Volatile private var lastAudioHealthDiagnostics: String = ""

    /** Audio-input health stats from the most recent turn end (may be empty). */
    fun lastAudioHealthDiagnostics(): String = lastAudioHealthDiagnostics

    // Application context retained only while a mic-routing override is active,
    // so we can release Bluetooth SCO when capture ends. Application context
    // ONLY — never an Activity — because the engine is a process-wide singleton
    // and would otherwise leak the chat screen.
    @Volatile private var routeAppContext: Context? = null

    // Plain-words description of the input device chosen for the most recent
    // startRecording: the requested route plus the actual active device before
    // and after the mic opened. The UI reads this after startRecording returns
    // and writes it to the event log, so the user can confirm which mic is
    // really in use (e.g. that a connected Bluetooth headset is being captured
    // from, not the built-in mic). Read on the main thread.
    @Volatile private var lastMicRouteDiagnostics: String = ""

    /** Input-device routing summary from the most recent startRecording (may be
     *  empty when no Context was supplied to choose/route the input). */
    fun lastMicRouteDiagnostics(): String = lastMicRouteDiagnostics

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
        val webRtcMode: Int = VadMethods.WEBRTC_DEFAULT_MODE,
        val graceMs: Long = 0L,
        val logging: Boolean = false,
        /** Energy gate / threshold tuning, user-editable in advanced settings. */
        val tuning: VadTuning = VadTuning(),
        /** Detected speech must accumulate this long before the turn counts as
         *  started; 0 = first speech frame starts it (historic behaviour). */
        val minSpeechMs: Long = 0L,
        /** When true, collect per-turn microphone input-health stats (the
         *  "Audio Health" diagnostic). Independent of [logging]. */
        val audioHealth: Boolean = false
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
     *
     * [context] enables Bluetooth-first input routing: when supplied, a
     * connected Bluetooth headset is selected as the capture device (else the
     * built-in mic), re-evaluated on every call so a headset connecting or
     * dropping between turns is honoured. It also lets us record which input is
     * actually live before/after the mic opens (see [lastMicRouteDiagnostics]).
     * Null keeps the legacy behaviour (whatever the OS routes by default).
     */
    @SuppressLint("MissingPermission")
    fun startRecording(
        context: Context? = null,
        vad: VadConfig? = null,
        onEndOfTurn: (() -> Unit)? = null,
        onNoSpeechTimeout: (() -> Unit)? = null
    ): Boolean {
        val vadLog = vad?.logging == true
        val audioHealth = vad?.audioHealth == true
        if (isCapturing) {
            if (vadLog) Log.w(TAG, "[Turn ${turnNumber}] startRecording called while isCapturing=true — returning early WITHOUT resetting state!")
            return true
        }
        turnNumber++
        synchronized(chunkLock) {
            capturedChunks.clear()
            capturedCount = 0
        }
        lastVadDiagnostics = ""
        lastAudioHealthDiagnostics = ""
        lastMicRouteDiagnostics = ""
        vadConfig = vad
        onVadEndOfTurn = onEndOfTurn
        onVadNoSpeech = onNoSpeechTimeout
        if (vadLog) Log.i(TAG, "[Turn $turnNumber] START: silenceMs=${vad?.silenceMs} noSpeechMs=${vad?.noSpeechMs} " +
                "graceMs=${vad?.graceMs} method=${vad?.method} webRtcMode=${vad?.webRtcMode}")

        // Bluetooth-first input routing. Re-run every turn (cheap, idempotent
        // when the device is already selected) so a headset that connects or
        // drops between turns is honoured without restarting the loop. Done
        // before the AudioRecord opens so capture lands on the chosen device.
        val micRoute = context?.let {
            routeAppContext = it.applicationContext
            MicRouteSelector.selectPreferredInput(it)
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

        // Point this specific recorder at the chosen device too. The
        // communication device selected above starts/owns the Bluetooth SCO
        // link; this nudges the AudioRecord onto it (best-effort — null/unset
        // just leaves OS default routing).
        micRoute?.requested?.let { try { record.setPreferredDevice(it) } catch (_: Throwable) {} }

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

        // Record which input is actually live the instant the mic opened. With
        // Bluetooth this may still read "built-in mic" for a beat while SCO
        // connects — the AudioHealthMonitor's periodic re-check logs the switch
        // when it lands — but capturing the requested route plus the before/
        // after device here makes "which mic" explicit in the event log, which
        // is the whole point of this line.
        if (micRoute != null) {
            val after = MicRouteSelector.label(record.routedDevice)
            lastMicRouteDiagnostics =
                "requested ${micRoute.requestedLabel} " +
                "(Bluetooth headset ${if (micRoute.bluetoothAvailable) "connected" else "not connected"}); " +
                "active input before open ${micRoute.beforeLabel}, after open $after"
        }

        val readSize = bufferBytes / 2 // shorts per read
        // Build the detector once per recording, off the main thread. The
        // capture loop owns the silence/no-speech timers (so the user's
        // configured timings behave identically no matter which detector is
        // chosen); the detector only answers "is this frame speech?".
        val cfg = vadConfig
        val detector = cfg?.let { VadFactory.create(it.method, SAMPLE_RATE, it.webRtcMode, it.tuning) }
        detector?.reset()
        // Silence and no-speech windows are measured in *audio* time — the
        // number of samples actually captured — not wall-clock time. The
        // capture loop can stall (CPU contention while the Whisper model
        // cold-loads on the first turn, GC, the device under load, etc.). With
        // a wall-clock timer that stall keeps "elapsing" even though no audio
        // was read, so the instant the loop resumed it saw more than the
        // silence window had passed and fired end-of-turn — cutting the user
        // off mid-sentence ("dropped like a hot potato", worst on the first
        // turn). Counting samples makes the timers immune to that: a stall just
        // pauses the audio clock. AudioRecord may drop samples on overflow
        // during a stall, so this can only ever under-count — it never invents
        // phantom silence — which is exactly the safe direction.
        val silenceSampleTarget = (cfg?.silenceMs ?: 0L) * SAMPLE_RATE / 1000
        val noSpeechSampleTarget = (cfg?.noSpeechMs ?: 0L) * SAMPLE_RATE / 1000
        val graceSampleTarget = (cfg?.graceMs ?: 0L) * SAMPLE_RATE / 1000
        val minSpeechSampleTarget = (cfg?.minSpeechMs ?: 0L) * SAMPLE_RATE / 1000
        val tuning = cfg?.tuning ?: VadTuning()
        val tn = turnNumber
        captureJob = CoroutineScope(Dispatchers.IO).launch {
            // The effective speech gate for log lines — mirrors the detectors'
            // own computation so the logs show the user's tuned values, not
            // stale hardcoded defaults. "enter/exit" when hysteresis is on.
            fun gateOf(floor: Double): String {
                if (!tuning.gateEnabled) return "off"
                val enter = maxOf(floor * tuning.floorFactor, tuning.minSpeechRms)
                    .coerceAtMost(tuning.energyCeiling)
                return if (tuning.hysteresisEnabled) {
                    "${enter.toInt()}/${(enter * tuning.hysteresisExitRatio).toInt()}"
                } else {
                    "${enter.toInt()}"
                }
            }
            val readBuffer = ShortArray(readSize)
            // Optional microphone input-health monitor for this turn (the "Audio
            // Health" diagnostic). Only built when the user turned it on, so
            // there's zero per-frame cost otherwise. Tracks every received frame
            // — independent of the VAD detector — so it still reports when the
            // detector heard nothing.
            val audioHealthMonitor = if (audioHealth) AudioHealthMonitor(record) else null
            var speechStarted = false
            var silenceSamples = 0L
            var preSpeechSamples = 0L
            // Contiguous run of speech frames; a turn only "starts" once this
            // reaches minSpeechSampleTarget, so a door slam / cough can't open
            // a turn when the user has raised the minimum speech duration.
            var speechRunSamples = 0L
            var graceSamples = 0L
            var graceComplete = graceSampleTarget <= 0
            var vadFired = false
            var totalSamplesRead = 0L
            var lastHeartbeatSamples = 0L
            val heartbeatIntervalSamples = SAMPLE_RATE * 2L
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
                        totalSamplesRead += read

                        // Audio Health tracks all received frames, regardless of
                        // VAD state, so it can report a dead/clipping mic even on
                        // a turn where the detector never fired.
                        audioHealthMonitor?.accept(readBuffer, read, totalSamplesRead)

                        if (detector != null && !vadFired) {
                            val frameRms = if (vadLog) rmsOfShort(readBuffer, read) else 0.0
                            if (!graceComplete) {
                                detector.accept(readBuffer, read)
                                graceSamples += read
                                preSpeechSamples += read
                                if (graceSamples >= graceSampleTarget) {
                                    graceComplete = true
                                    detector.reset()
                                    if (vadLog) Log.i(TAG, "[Turn $tn] GRACE_DONE: graceMs=${graceSamples * 1000 / SAMPLE_RATE} " +
                                            "lastFrameRms=${frameRms.toInt()} — detector reset, calibrating from ambient")
                                }
                            } else {
                                val wasSpeech = speechStarted
                                val isSpeech = detector.accept(readBuffer, read)
                                lastVadDiagnostics = detector.diagnostics()
                                if (isSpeech) {
                                    speechRunSamples += read
                                    if (speechStarted || speechRunSamples >= minSpeechSampleTarget) {
                                        if (vadLog && !wasSpeech) {
                                            val floor = detector.currentNoiseFloor()
                                            Log.i(TAG, "[Turn $tn] SPEECH_START: rms=${frameRms.toInt()} floor=${floor.toInt()} " +
                                                    "threshold=${gateOf(floor)} preSpeechMs=${preSpeechSamples * 1000 / SAMPLE_RATE} " +
                                                    "totalMs=${totalSamplesRead * 1000 / SAMPLE_RATE}")
                                        }
                                        speechStarted = true
                                        silenceSamples = 0L
                                    } else {
                                        // Speech heard but not yet long enough to
                                        // open the turn — still pre-speech time.
                                        preSpeechSamples += read
                                    }
                                } else if (speechStarted) {
                                    silenceSamples += read
                                } else {
                                    speechRunSamples = 0L
                                    preSpeechSamples += read
                                }
                                if (speechStarted && silenceSamples >= silenceSampleTarget) {
                                    if (vadLog) {
                                        val floor = detector.currentNoiseFloor()
                                        Log.w(TAG, "[Turn $tn] FINALIZE END_OF_TURN: rms=${frameRms.toInt()} " +
                                                "floor=${floor.toInt()} threshold=${gateOf(floor)} " +
                                                "speechStarted=$speechStarted silenceMs=${silenceSamples * 1000 / SAMPLE_RATE} " +
                                                "targetSilenceMs=${silenceSampleTarget * 1000 / SAMPLE_RATE} " +
                                                "totalAudioMs=${totalSamplesRead * 1000 / SAMPLE_RATE} " +
                                                "graceMs=${graceSamples * 1000 / SAMPLE_RATE} " +
                                                "diag=${detector.diagnostics()}")
                                    }
                                    if (audioHealthMonitor != null) lastAudioHealthDiagnostics = audioHealthMonitor.summarize(vadHeardSpeech = true)
                                    vadFired = true
                                    onVadEndOfTurn?.invoke()
                                } else if (!speechStarted && preSpeechSamples >= noSpeechSampleTarget) {
                                    if (vadLog) {
                                        val floor = detector.currentNoiseFloor()
                                        Log.w(TAG, "[Turn $tn] FINALIZE NO_SPEECH: rms=${frameRms.toInt()} " +
                                                "floor=${floor.toInt()} threshold=${gateOf(floor)} " +
                                                "preSpeechMs=${preSpeechSamples * 1000 / SAMPLE_RATE} " +
                                                "targetNoSpeechMs=${noSpeechSampleTarget * 1000 / SAMPLE_RATE} " +
                                                "totalAudioMs=${totalSamplesRead * 1000 / SAMPLE_RATE} " +
                                                "diag=${detector.diagnostics()}")
                                    }
                                    if (audioHealthMonitor != null) lastAudioHealthDiagnostics = audioHealthMonitor.summarize(vadHeardSpeech = false)
                                    vadFired = true
                                    onVadNoSpeech?.invoke()
                                }
                                if (vadLog && totalSamplesRead - lastHeartbeatSamples >= heartbeatIntervalSamples) {
                                    lastHeartbeatSamples = totalSamplesRead
                                    val floor = detector.currentNoiseFloor()
                                    Log.d(TAG, "[Turn $tn] HEARTBEAT: rms=${frameRms.toInt()} floor=${floor.toInt()} " +
                                            "threshold=${gateOf(floor)} speechStarted=$speechStarted " +
                                            "silenceMs=${silenceSamples * 1000 / SAMPLE_RATE} " +
                                            "totalMs=${totalSamplesRead * 1000 / SAMPLE_RATE}")
                                }
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

        // Decode parameters come straight from the (global) advanced voice
        // settings. Read here, in the single funnel every transcription flows
        // through, so the chat, the assistant overlay and any future caller
        // all honour them without per-call-site plumbing.
        val prefs = Preferences.getPreferences(context.applicationContext, "")
        val useBeam = prefs.getWhisperDecoder() != "greedy"
        val beamSize = prefs.getWhisperBeamSize()
        val temperature = prefs.getWhisperTemperature()
        val suppressBlank = prefs.getWhisperSuppressBlank()
        val singleSegment = prefs.getWhisperSingleSegment()
        val noContext = !prefs.getWhisperUsePrevContext()
        val initialPrompt = prefs.getWhisperInitialPrompt()
        val cleanup = prefs.getWhisperCleanupTranscript()
        val debugParams = prefs.getWhisperDebugParams()

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
                    val text = LocalWhisperNative.transcribeNative(
                        handle, samples, SAMPLE_RATE, language,
                        useBeam, beamSize, temperature,
                        suppressBlank, singleSegment, noContext, initialPrompt
                    )
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    val summary = "model=$activeModelId audio=${audioMs}ms decode=${elapsed}ms " +
                            "decoder=${if (useBeam) "beam($beamSize)" else "greedy"} temp=$temperature " +
                            "suppressBlank=$suppressBlank singleSegment=$singleSegment noContext=$noContext " +
                            "prompt=${if (initialPrompt.isBlank()) "none" else "${initialPrompt.length} chars"} " +
                            "cleanup=$cleanup"
                    Log.i(TAG, "Transcribed: $summary")
                    if (debugParams) {
                        try {
                            Logger.log(context.applicationContext, "event", "WhisperParams", "debug", summary)
                        } catch (_: Throwable) { /* diagnostics must never break STT */ }
                    }
                    // After stripping non-speech markers, Whisper-generated
                    // punctuation around them ("[Music].", "♪♪♪…") can leave
                    // a content-less remainder like "." or "…". Treat that as
                    // empty so hands-free re-arms the mic instead of submitting
                    // bare punctuation as a "real" transcript.
                    val filtered = if (cleanup) filterNonSpeechMarkers(text) else text.trim()
                    if (filtered.any { it.isLetterOrDigit() }) filtered else null
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
        // Capture is over (abort / no-speech / user stop). Release any Bluetooth
        // SCO routing so a headset isn't left in call mode. Normal end-of-turn
        // goes through stopAndTranscribe instead, which keeps the route up so a
        // continuous hands-free loop doesn't re-negotiate SCO every turn.
        clearMicRouting()
    }

    /**
     * Releases any Bluetooth SCO / communication-device routing taken for
     * capture, returning input to the OS default (built-in). Safe to call when
     * nothing was routed. Exposed so the hands-free loop can tear routing down
     * when it stops without going through [cancel].
     */
    fun clearMicRouting() {
        val ctx = routeAppContext ?: return
        routeAppContext = null
        try { MicRouteSelector.clear(ctx) } catch (_: Throwable) {}
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

/**
 * Per-recording microphone input-health monitor for the optional "Audio Health"
 * diagnostic. Where the VAD detectors answer "was there speech?", this answers
 * "did the microphone deliver usable audio?" — a muted/dead mic shows up as
 * near-silent frames, input that's too hot shows up as clipped frames, and an
 * input-route change mid-capture (e.g. a Bluetooth/SCO headset connecting or
 * dropping) is flagged. Built only when the user enables Audio Health, so it
 * costs nothing otherwise; one cheap pass over each captured frame.
 *
 * The summary line is shaped to sit cleanly next to a VAD line — it carries its
 * own "Audio Health" label — and, like the detectors, ends with plain-words
 * hints that name the likely problem and what to try.
 */
private class AudioHealthMonitor(private val record: AudioRecord) {
    private var frames = 0L
    private var samples = 0L
    private var rmsSum = 0.0
    private var rmsMax = 0.0
    private var peakMax = 0
    private var nearZeroFrames = 0L
    private var clippedFrames = 0L

    private var routeKnown = false
    private var initialRouteId = -1
    private var initialRouteLabel = "unavailable"
    private var initialRouteType = -1
    private var currentRouteLabel = "unavailable"
    private var currentRouteType = -1
    private var routeChanged = false
    private var bluetoothSeen = false
    private var lastRouteCheckSamples = 0L

    // A Bluetooth SCO mic link takes a beat to come up after capture starts, so
    // the first second or so is captured on the built-in mic and Android then
    // switches the route to the headset. That built-in→SCO transition is normal
    // warm-up, NOT the user (dis)connecting anything — distinguishing it from the
    // opposite SCO→built-in transition (the headset actually dropping) is the
    // difference between a reassuring note and a misleading "disconnect your
    // headset" hint for a headset the user kept on the whole time.
    private val scoWarmUp: Boolean
        get() = routeChanged && initialRouteType != AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                currentRouteType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    private val scoDropped: Boolean
        get() = routeChanged && initialRouteType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                currentRouteType != AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    init {
        // Capture the route at the start of the turn (called right after
        // startRecording, so the input route has settled).
        checkRoute()
    }

    /** Fold one captured frame into the running stats. [totalSamplesRead] is the
     *  turn's running sample count, used only to pace the (cheap) route checks. */
    fun accept(buf: ShortArray, len: Int, totalSamplesRead: Long) {
        if (len <= 0) return
        frames++
        samples += len
        var peak = 0
        var sumSq = 0.0
        for (i in 0 until len) {
            val s = buf[i].toInt()
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
            sumSq += s.toDouble() * s.toDouble()
        }
        val rms = kotlin.math.sqrt(sumSq / len)
        rmsSum += rms
        if (rms > rmsMax) rmsMax = rms
        if (peak > peakMax) peakMax = peak
        if (peak <= NEAR_ZERO_PEAK) nearZeroFrames++
        if (peak >= CLIP_PEAK) clippedFrames++
        // Re-check the input route ~once a second: cheap, and enough to catch a
        // headset (dis)connecting mid-turn without querying every frame.
        if (totalSamplesRead - lastRouteCheckSamples >= record.sampleRate.toLong()) {
            lastRouteCheckSamples = totalSamplesRead
            checkRoute()
        }
    }

    private fun checkRoute() {
        val dev = try { record.routedDevice } catch (_: Throwable) { null }
        val id = dev?.id ?: -1
        val label = deviceLabel(dev)
        if (dev?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) bluetoothSeen = true
        val type = dev?.type ?: -1
        if (!routeKnown) {
            routeKnown = true
            initialRouteId = id
            initialRouteLabel = label
            initialRouteType = type
            currentRouteLabel = label
            currentRouteType = type
        } else if (id != initialRouteId) {
            routeChanged = true
            currentRouteLabel = label
            currentRouteType = type
        }
    }

    /**
     * One-line summary + hints, parallel in shape to the detectors' diagnostics().
     *
     * Hints are deliberately attributed to a *cause* — device/OS, settings, or
     * input level — instead of always telling the user to speak louder. The
     * key signal is the cross-check with [vadHeardSpeech]: if the mic delivered
     * a healthy, audible signal but the detector still found no speech, that is
     * a detection-settings problem, not the mic and not how the user spoke.
     */
    fun summarize(vadHeardSpeech: Boolean): String {
        val rate = record.sampleRate
        val tenths = if (rate > 0) samples * 10 / rate else 0L
        val avgRms = if (frames > 0) (rmsSum / frames).toInt() else 0
        val ch = record.channelCount
        val chLabel = when (ch) {
            1 -> "mono"
            2 -> "stereo"
            else -> "$ch-channel"
        }
        val routeText = when {
            scoWarmUp -> "$initialRouteLabel → $currentRouteLabel (Bluetooth connected after recording started)"
            scoDropped -> "$initialRouteLabel → $currentRouteLabel (Bluetooth headset dropped mid-recording)"
            routeChanged -> "$initialRouteLabel → $currentRouteLabel (changed mid-capture)"
            else -> initialRouteLabel
        }
        var s = "Audio Health ${tenths / 10}.${tenths % 10}s: $frames frames received, " +
                "RMS avg $avgRms/max ${rmsMax.toInt()}, peak max $peakMax, " +
                "near-zero $nearZeroFrames, clipped $clippedFrames, " +
                "$rate Hz, $ch ch ($chLabel), route $routeText"

        val nearSilent = frames > 0 && nearZeroFrames * 2 >= frames && peakMax <= NEAR_ZERO_PEAK * 4
        // "Healthy" = real, audible audio arrived and most frames carried signal.
        val inputHealthy = frames > 0 && peakMax > QUIET_PEAK && nearZeroFrames * 2 < frames

        // Hints are written for a non-technical reader: plain words, the real
        // on-screen setting names and where to find them, an explicit "this
        // isn't you" when it's the phone or the settings, and one concrete
        // thing to do. The raw numbers above are for bug reports; these lines
        // are the part a person acts on. Most likely/severe first.
        val hints = ArrayList<String>()

        if (frames == 0L) {
            hints.add("No sound reached the app at all. This is the phone or another app, not you: usually the microphone permission is off, or a call/another recording app grabbed the mic. What to do: in Android Settings > Apps > SpeakGPT > Permissions make sure Microphone is allowed, close other apps that record audio, then try again.")
        } else if (nearSilent) {
            hints.add("The microphone was on but picked up almost nothing the whole time — so this is the phone, not how loudly you spoke. What to do: check SpeakGPT has microphone permission, make sure nothing is covering the mic, and close other apps that might be using it. On a Bluetooth headset, try the phone's own mic.")
        }
        if (frames > 0 && rate != LocalWhisperEngine.SAMPLE_RATE) {
            hints.add("The mic recorded at the wrong speed ($rate Hz, not the ${LocalWhisperEngine.SAMPLE_RATE / 1000} kHz the app needs) — an Android/phone audio problem, not a setting you changed. What to do: restart the app, and the phone if it keeps happening.")
        }
        if (!vadHeardSpeech && inputHealthy && clippedFrames == 0L) {
            hints.add("Your mic worked fine and clearly picked up sound, but the app decided none of it was speech and stopped listening. This is a settings problem, not you or your mic — voice detection is set too strict. Easiest fix: Settings > Voice & speech > Voice detection method > Silero (it's best at telling speech from background noise). Or, in Settings > Voice & speech > Advanced & debugging, turn down 'Minimum speech energy' (if you use the Energy method) or 'Silero speech threshold' (if you use Silero).")
        }
        if (clippedFrames > 0) {
            hints.add("The sound was so loud it distorted (it 'clipped'). What to do: hold the phone a little farther from your mouth — you don't need to speak right onto the mic.")
        } else if (!nearSilent && peakMax in (NEAR_ZERO_PEAK + 1)..QUIET_PEAK) {
            hints.add("The sound coming in was quite quiet (but not silent) — could be the mic, the distance, or a quiet room, not necessarily you. What to do: move the phone a bit closer; or if you're often far away, turn down 'Minimum speech energy' in Settings > Voice & speech > Advanced & debugging so quiet speech still counts.")
        }
        if (scoWarmUp) {
            hints.add("The recording started on the phone's microphone and switched to your Bluetooth headset a moment later. This is normal Bluetooth 'warm-up' — the headset's mic link takes a beat to connect after the mic opens — NOT your headset dropping or you disconnecting anything. Only the very start was on the phone mic; the rest was the headset. Nothing to fix; if the first word ever gets clipped, pause briefly before speaking after the mic opens.")
        } else if (scoDropped) {
            hints.add("Your Bluetooth headset dropped partway through and recording fell back to the phone's microphone, which can chop the recording. This one IS a real drop, not warm-up. What to do: check the headset's battery and that it stays in range; if it keeps dropping, turn it off and use the phone's own mic.")
        } else if (routeChanged) {
            hints.add("The audio switched to a different microphone partway through, which can chop the recording in half. What to do: connect or disconnect headsets before you start talking, not while you speak.")
        } else if (bluetoothSeen) {
            hints.add("You're recording through a Bluetooth headset (SCO), which is low quality and drops out easily. What to do: if recognition is poor, turn the headset off and use the phone's own microphone.")
        }
        if (ch != 1) {
            hints.add("The mic gave $ch-channel audio when the app expects mono — an unusual Android quirk. It may still work; if recognition is poor, it's worth reporting.")
        }

        // Nothing wrong worth flagging: say so, so a healthy turn doesn't read
        // as a non-answer.
        if (hints.isEmpty() && frames > 0) {
            hints.add("The microphone input looks healthy — no problem with the audio itself.")
        }

        // One hint per line so several plain-language tips stay readable.
        if (hints.isNotEmpty()) s += "\n  - " + hints.joinToString("\n  - ")
        return s
    }

    // Shared with the mic-open route line so both always name a device the
    // same way (and pick up the headset's product name when available).
    private fun deviceLabel(dev: AudioDeviceInfo?): String = MicRouteSelector.label(dev)

    private companion object {
        // Peak amplitude at/below this (out of 32767) is treated as digital
        // silence — a frame that carried essentially nothing.
        const val NEAR_ZERO_PEAK = 4
        // A frame touching the 16-bit rail is clipping (range -32768..32767).
        const val CLIP_PEAK = 32767
        // Peak below this is "very quiet" (~ -36 dBFS) but not silent.
        const val QUIET_PEAK = 500
    }
}
