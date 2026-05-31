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

import android.util.Log

/**
 * Voice-activity detection used by the hands-free Whisper loop. Whisper has
 * no end-of-speech signal of its own, so the capture loop feeds raw PCM
 * frames to a detector and asks "is this speech?"; the loop owns the
 * silence/no-speech *timers* (so the user's configured timings are honoured
 * identically regardless of which detector is selected) — the detector only
 * answers the per-frame speech question.
 *
 * Implementations:
 *   - [EnergyVad]  pure-Kotlin RMS-vs-adaptive-noise-floor. Zero deps, but
 *     treats any loud sound as speech.
 *   - [WebRtcVad]  the WebRTC GMM voice detector (via libfvad in native
 *     code). Much better at rejecting non-voice noise; default.
 *   - Silero (neural) is planned as a third option; [VadFactory] is the
 *     single place a new implementation gets wired in.
 */
interface VoiceActivityDetector {
    /** Reset internal state at the start of a new recording. */
    fun reset()

    /**
     * Feed [len] PCM-16 samples (16 kHz mono). Returns true if speech was
     * detected anywhere in this chunk. Implementations may buffer internally
     * to satisfy fixed frame-size requirements.
     */
    fun accept(frame: ShortArray, len: Int): Boolean

    /** Release any native resources. Safe to call more than once. */
    fun close()
}

/** Stable ids persisted in preferences and shown in the settings picker. */
object VadMethods {
    const val ENERGY = "energy"
    const val WEBRTC = "webrtc"
    const val SILERO = "silero" // reserved; not yet implemented
    // Energy is the default: dependency-free and works on every device. WebRTC
    // needs a native lib that isn't always present (and silently falls back to
    // energy when it isn't), so it's opt-in rather than the default.
    const val DEFAULT = ENERGY

    /**
     * WebRTC aggressiveness range (libfvad). 0 = "quality" (most sensitive,
     * hears the most speech), 3 = "very aggressive" (rejects the most noise,
     * most likely to drop quiet/distant speech). Exposed to the user as a
     * "detection sensitivity" picker; default 0 because missing the user is
     * the cardinal sin for a hands-free assistant — the higher modes were
     * observed clipping normal speech entirely when the mic isn't close/loud
     * (e.g. talking with a headset on), making the loop time out as if nothing
     * was said. WebRTC still rejects steady noise (fan/AC) at mode 0 because it
     * adapts to stationary noise.
     */
    const val WEBRTC_MIN_MODE = 0
    const val WEBRTC_MAX_MODE = 3
    const val WEBRTC_DEFAULT_MODE = 0
}

/** Builds the detector for the selected method, falling back to energy. */
object VadFactory {
    private const val TAG = "VadFactory"

    fun create(
        method: String,
        sampleRate: Int,
        webRtcMode: Int = VadMethods.WEBRTC_DEFAULT_MODE
    ): VoiceActivityDetector {
        return when (method) {
            VadMethods.WEBRTC -> {
                // Aggressiveness is user-tunable (see VadMethods.WEBRTC_*). If
                // the native lib is missing or init fails, transparently use
                // energy so hands-free still works.
                val mode = webRtcMode.coerceIn(VadMethods.WEBRTC_MIN_MODE, VadMethods.WEBRTC_MAX_MODE)
                WebRtcVad.create(mode = mode, sampleRate = sampleRate) ?: run {
                    Log.w(TAG, "WebRTC VAD unavailable, falling back to energy")
                    EnergyVad()
                }
            }
            // VadMethods.SILERO -> SileroVad.create(...) ?: EnergyVad()   // TODO
            else -> EnergyVad()
        }
    }
}

/**
 * Energy-based VAD: a frame counts as speech when its RMS is clearly above an
 * adaptive noise floor (or an absolute minimum, so a dead-quiet room with a
 * tiny floor doesn't trip on faint fluctuations). Cheap and dependency-free;
 * the trade-off is that any sufficiently loud sound reads as "speech".
 */
class EnergyVad(
    private val minSpeechRms: Double = MIN_SPEECH_RMS,
    private val floorFactor: Double = SPEECH_FLOOR_FACTOR
) : VoiceActivityDetector {

    private var noiseFloor = 0.0
    private var floorInit = false

    override fun reset() {
        noiseFloor = 0.0
        floorInit = false
    }

    override fun accept(frame: ShortArray, len: Int): Boolean {
        if (len <= 0) return false
        val rms = rmsOf(frame, len)
        if (!floorInit) { noiseFloor = rms; floorInit = true }
        val threshold = maxOf(noiseFloor * floorFactor, minSpeechRms)
        return if (rms >= threshold) {
            true
        } else {
            // Track the ambient floor on quiet frames so the threshold adapts
            // to the room over a few hundred ms.
            noiseFloor = noiseFloor * 0.97 + rms * 0.03
            false
        }
    }

    override fun close() { /* no native resources */ }

    private fun rmsOf(buf: ShortArray, len: Int): Double {
        var sum = 0.0
        for (i in 0 until len) {
            val s = buf[i].toDouble()
            sum += s * s
        }
        return kotlin.math.sqrt(sum / len)
    }

    companion object {
        // These are the numbers most likely to need on-device tuning.
        const val MIN_SPEECH_RMS = 600.0
        const val SPEECH_FLOOR_FACTOR = 2.5
    }
}

/**
 * WebRTC voice detector via libfvad (bundled in libphosphorwhisper.so).
 * libfvad requires fixed 10/20/30 ms frames, so incoming variable-size
 * chunks are reassembled into 30 ms frames internally; a chunk is "speech"
 * if any complete frame within it was classified as voice.
 */
class WebRtcVad private constructor(
    private var handle: Long,
    private val frameSamples: Int
) : VoiceActivityDetector {

    private val pending = ShortArray(frameSamples)
    private var pendingCount = 0

    override fun reset() {
        pendingCount = 0
        if (handle != 0L) {
            try { WebRtcVadNative.nativeReset(handle) } catch (_: Throwable) {}
        }
    }

    override fun accept(frame: ShortArray, len: Int): Boolean {
        if (handle == 0L || len <= 0) return false
        var i = 0
        var speech = false
        while (i < len) {
            val n = minOf(frameSamples - pendingCount, len - i)
            System.arraycopy(frame, i, pending, pendingCount, n)
            pendingCount += n
            i += n
            if (pendingCount == frameSamples) {
                val r = try {
                    WebRtcVadNative.nativeProcess(handle, pending, frameSamples)
                } catch (_: Throwable) { -1 }
                if (r == 1) speech = true
                pendingCount = 0
            }
        }
        return speech
    }

    override fun close() {
        if (handle != 0L) {
            try { WebRtcVadNative.nativeFree(handle) } catch (_: Throwable) {}
            handle = 0L
        }
    }

    companion object {
        private const val TAG = "WebRtcVad"

        /** @param mode libfvad aggressiveness 0..3. Returns null if unavailable. */
        fun create(mode: Int, sampleRate: Int): WebRtcVad? {
            if (!WebRtcVadNative.ensureLoaded()) return null
            val handle = try {
                WebRtcVadNative.nativeNew(mode, sampleRate)
            } catch (t: Throwable) {
                Log.w(TAG, "nativeNew threw", t)
                0L
            }
            if (handle == 0L) return null
            val frameSamples = sampleRate / 1000 * 30 // 30 ms frame
            return WebRtcVad(handle, frameSamples)
        }
    }
}
