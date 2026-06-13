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

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD — the neural third detector ([VadMethods.SILERO]). A ~2 MB
 * speech/not-speech model (assets/silero_vad.onnx, pinned silero-vad v5.1.2,
 * MIT) run through ONNX Runtime. Unlike the Energy and WebRTC detectors it
 * needs no energy gate, no noise-floor math and no per-room tuning: it was
 * trained to tell human speech from fans, music and changing room noise.
 * This is the same VAD component the LiveKit voice-agent stack uses — just
 * running on the phone instead of a server.
 *
 * Only the probability threshold applies here (plus the shared hysteresis /
 * speech-hold semantics); the energy-gate settings are ignored by design.
 */

/**
 * Process-wide holder for the ONNX session. Loaded once (the session costs
 * real memory and ~100 ms of init) and kept for the process lifetime, like
 * the Whisper context. All failures are cached and non-fatal: when this
 * isn't loaded, [VadFactory] silently falls back to the Energy detector so
 * hands-free always works.
 */
object SileroVadRuntime {
    private const val TAG = "SileroVadRuntime"
    private const val ASSET_NAME = "silero_vad.onnx"

    const val SAMPLE_RATE = 16_000
    const val FRAME_SAMPLES = 512   // the v5 model decides in 32 ms windows
    const val CONTEXT_SAMPLES = 64  // v5 carries 4 ms of context between windows

    @Volatile private var session: OrtSession? = null
    @Volatile private var loadFailed = false
    // Interface variant probed at load: whether the graph wants the 64-sample
    // context prepended to the input, and whether "sr" is a scalar or [1].
    @Volatile private var inputWithContext = true
    @Volatile private var srAsScalar = true

    fun isLoaded(): Boolean = session != null

    /**
     * Loads the model from assets and probes which exact tensor shapes this
     * model build accepts (silero-vad shipped both context-managed and plain
     * inputs across versions; guessing wrong throws). Safe to call from any
     * thread, repeatedly; only the first call does work.
     */
    fun ensureLoaded(context: Context): Boolean {
        if (session != null) return true
        if (loadFailed) return false
        synchronized(this) {
            if (session != null) return true
            if (loadFailed) return false
            try {
                val bytes = context.applicationContext.assets.open(ASSET_NAME).use { it.readBytes() }
                val env = OrtEnvironment.getEnvironment()
                val s = env.createSession(bytes, OrtSession.SessionOptions())

                // Probe the interface with silence. Try (context+input, scalar
                // sr) first — the v5.1.2 layout — then the other combinations,
                // mirroring how WebRtcVad self-tests its frame length.
                var probed = false
                outer@ for (withContext in booleanArrayOf(true, false)) {
                    for (scalarSr in booleanArrayOf(true, false)) {
                        if (probeSession(env, s, withContext, scalarSr)) {
                            inputWithContext = withContext
                            srAsScalar = scalarSr
                            probed = true
                            break@outer
                        }
                    }
                }
                if (!probed) {
                    Log.w(TAG, "Silero model loaded but no probed input layout was accepted")
                    try { s.close() } catch (_: Throwable) {}
                    loadFailed = true
                    return false
                }
                session = s
                Log.i(TAG, "Silero VAD ready (context=$inputWithContext, scalarSr=$srAsScalar)")
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "Silero VAD failed to load", t)
                loadFailed = true
                return false
            }
        }
    }

    private fun probeSession(env: OrtEnvironment, s: OrtSession, withContext: Boolean, scalarSr: Boolean): Boolean {
        return try {
            val state = FloatArray(2 * 1 * 128)
            runFrame(env, s, FloatArray(FRAME_SAMPLES), FloatArray(CONTEXT_SAMPLES), state, withContext, scalarSr)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * One 32 ms inference. [state] (2x1x128) is updated in place from the
     * model's stateN output; [context] is the previous frame's tail when the
     * interface wants it. Returns the speech probability 0..1.
     */
    fun runFrame(
        env: OrtEnvironment,
        s: OrtSession,
        frame: FloatArray,
        context: FloatArray,
        state: FloatArray,
        withContext: Boolean = inputWithContext,
        scalarSr: Boolean = srAsScalar
    ): Float {
        val input: FloatArray = if (withContext) {
            FloatArray(CONTEXT_SAMPLES + FRAME_SAMPLES).also {
                System.arraycopy(context, 0, it, 0, CONTEXT_SAMPLES)
                System.arraycopy(frame, 0, it, CONTEXT_SAMPLES, FRAME_SAMPLES)
            }
        } else {
            frame
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong())).use { inputT ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { stateT ->
                OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(longArrayOf(SAMPLE_RATE.toLong())),
                    if (scalarSr) longArrayOf() else longArrayOf(1)
                ).use { srT ->
                    s.run(mapOf("input" to inputT, "state" to stateT, "sr" to srT)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val prob = (result.get(0).value as Array<FloatArray>)[0][0]
                        @Suppress("UNCHECKED_CAST")
                        val stateN = result.get(1).value as Array<Array<FloatArray>>
                        var i = 0
                        for (a in 0 until 2) for (b in 0 until 1) for (c in 0 until 128) {
                            state[i++] = stateN[a][b][c]
                        }
                        return prob
                    }
                }
            }
        }
    }

    fun environment(): OrtEnvironment = OrtEnvironment.getEnvironment()
    fun sessionOrNull(): OrtSession? = session
}

/**
 * The per-recording detector. Buffers incoming chunks into 512-sample model
 * frames, asks the network "is this speech?" per frame, and applies the same
 * hysteresis (enter threshold / lower exit threshold while speaking — Silero's
 * own recommended pairing is exit = threshold − 0.15) and speech-hold
 * semantics as the other detectors, so the user's settings mean the same
 * thing everywhere.
 */
class SileroVad private constructor(
    private val tuning: VadTuning,
    private val sampleRate: Int
) : VoiceActivityDetector {

    private val pending = FloatArray(SileroVadRuntime.FRAME_SAMPLES)
    private var pendingCount = 0
    private val contextBuf = FloatArray(SileroVadRuntime.CONTEXT_SAMPLES)
    private val state = FloatArray(2 * 1 * 128)

    private var inSpeech = false
    private var framesSinceSpeech = -1L

    // Per-recording stats for diagnostics.
    private var totalFrames = 0
    private var speechFrames = 0
    private var hysteresisHeldFrames = 0
    private var hangoverHeldFrames = 0
    private var maxProb = 0f
    private var probSum = 0.0
    private var inferenceFailures = 0

    private val enterThreshold: Double
        get() = tuning.sileroThreshold.coerceIn(0.05, 0.95)
    private val exitThreshold: Double
        get() = if (tuning.hysteresisEnabled) (enterThreshold - 0.15).coerceAtLeast(0.05) else enterThreshold

    override fun reset() {
        pendingCount = 0
        contextBuf.fill(0f)
        state.fill(0f)
        inSpeech = false
        framesSinceSpeech = -1L
        totalFrames = 0
        speechFrames = 0
        hysteresisHeldFrames = 0
        hangoverHeldFrames = 0
        maxProb = 0f
        probSum = 0.0
        inferenceFailures = 0
    }

    override fun accept(frame: ShortArray, len: Int): Boolean {
        val session = SileroVadRuntime.sessionOrNull() ?: return false
        val env = SileroVadRuntime.environment()
        if (len <= 0) return false
        var i = 0
        var speech = false
        val frameSamples = SileroVadRuntime.FRAME_SAMPLES
        while (i < len) {
            val n = minOf(frameSamples - pendingCount, len - i)
            var j = 0
            while (j < n) {
                pending[pendingCount + j] = frame[i + j] / 32768.0f
                j++
            }
            pendingCount += n
            i += n
            if (pendingCount == frameSamples) {
                val prob = try {
                    SileroVadRuntime.runFrame(env, session, pending, contextBuf, state)
                } catch (t: Throwable) {
                    inferenceFailures++
                    -1f
                }
                if (prob >= 0f) {
                    totalFrames++
                    probSum += prob
                    if (prob > maxProb) maxProb = prob
                    // Same Schmitt-trigger shape as the other detectors:
                    // enter at the full threshold, stay above the exit one.
                    val raw = prob >= (if (inSpeech) exitThreshold else enterThreshold)
                    if (raw && inSpeech && prob < enterThreshold) hysteresisHeldFrames++
                    var frameIsSpeech = raw
                    if (frameIsSpeech) {
                        framesSinceSpeech = 0
                    } else if (framesSinceSpeech >= 0) {
                        framesSinceSpeech++
                        if (tuning.hangoverMs > 0 &&
                            framesSinceSpeech * frameSamples * 1000 <= tuning.hangoverMs * sampleRate
                        ) {
                            frameIsSpeech = true
                            hangoverHeldFrames++
                        }
                    }
                    inSpeech = frameIsSpeech
                    if (frameIsSpeech) {
                        speechFrames++
                        speech = true
                    }
                    // The model carries 4 ms of context between windows.
                    System.arraycopy(
                        pending,
                        frameSamples - SileroVadRuntime.CONTEXT_SAMPLES,
                        contextBuf, 0, SileroVadRuntime.CONTEXT_SAMPLES
                    )
                }
                pendingCount = 0
            }
        }
        return speech
    }

    override fun currentNoiseFloor(): Double = 0.0 // not energy-based

    override fun diagnostics(): String {
        val audioTenths = totalFrames.toLong() * SileroVadRuntime.FRAME_SAMPLES * 10 / sampleRate
        val avg = if (totalFrames > 0) (probSum / totalFrames * 100).toInt() else 0
        var s = "Silero ${audioTenths / 10}.${audioTenths % 10}s: speech $speechFrames/$totalFrames frames, " +
                "maxProb ${(maxProb * 100).toInt()}%, avgProb $avg%, " +
                "threshold ${(enterThreshold * 100).toInt()}%"
        s += if (tuning.hysteresisEnabled) {
            "/${(exitThreshold * 100).toInt()}% (held $hysteresisHeldFrames)"
        } else {
            ", hyst off"
        }
        if (tuning.hangoverMs > 0) s += ", hold ${tuning.hangoverMs}ms (held $hangoverHeldFrames)"
        if (inferenceFailures > 0) s += ", failures $inferenceFailures"
        // Plain-words hints, same philosophy as the other detectors: the log
        // line should tell the user which knob to turn.
        if (speechFrames == 0 && totalFrames > 0) {
            s += if (maxProb >= enterThreshold * 0.5f) {
                " — speech-like audio peaked just under the threshold; lower the Silero speech threshold"
            } else {
                " — nothing speech-like was heard; check the microphone/distance"
            }
        }
        return s
    }

    override fun close() { /* session is process-wide, like the whisper context */ }

    companion object {
        private const val TAG = "SileroVad"

        /**
         * Returns a detector when the runtime is loaded and the rate matches
         * the model; null otherwise so [VadFactory] can fall back to Energy.
         * Loading itself (needs a Context for assets) happens earlier via
         * [SileroVadRuntime.ensureLoaded] — the UI does it on selection and
         * ChatActivity re-checks per hands-free session.
         */
        fun createIfLoaded(tuning: VadTuning, sampleRate: Int): SileroVad? {
            if (sampleRate != SileroVadRuntime.SAMPLE_RATE) {
                Log.w(TAG, "Unsupported sample rate $sampleRate for Silero VAD")
                return null
            }
            if (!SileroVadRuntime.isLoaded()) return null
            return SileroVad(tuning, sampleRate).also { it.reset() }
        }
    }
}
