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

    /**
     * Human-readable stats for the most recent recording, shown on-screen when a
     * hands-free turn times out so "it never heard me" can be diagnosed without
     * logcat. Empty by default; detectors that can explain themselves override it.
     */
    fun diagnostics(): String = ""
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
        // Anchor the noise floor to the quietest frame seen, not the first.
        // In hands-free, the next turn's mic opens the instant the AI stops
        // talking, so the first frame is often the user already mid-word.
        // Snapshotting that as "ambient" pinned the threshold above the user's
        // own speech volume, so every following frame read as silence and the
        // silence timer fired in the middle of a sentence. The running min
        // recalibrates the moment a natural gap between words slips through.
        if (!floorInit || rms < noiseFloor) {
            noiseFloor = rms
            floorInit = true
        }
        val threshold = maxOf(noiseFloor * floorFactor, minSpeechRms)
        return if (rms >= threshold) {
            true
        } else {
            // Below threshold = ambient — let the floor drift up so a noisier
            // room (fan turned on mid-recording) raises the bar over time.
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

    // Per-recording diagnostics so a "WebRTC never hears me" timeout can report
    // whether libfvad saw any voiced frames and how loud the input actually was.
    private var totalFrames = 0
    private var voicedFrames = 0       // libfvad raw vote
    private var gatedVoicedFrames = 0  // libfvad vote AND above the energy floor
    private var errorFrames = 0
    private var peakAbs = 0

    // Adaptive energy floor that gates libfvad's vote. The GMM still tags
    // continuous fan/AC rumble as voice on some devices (seen on Pixel 8 +
    // a desk fan: 99.8% voiced frames at mode 2 AND mode 3), which keeps
    // lastVoiceAt fresh forever and means the silence timer never fires —
    // hands-free "listens forever". Tracking the running-minimum RMS and
    // requiring the frame to clear floor*2.5 (or an absolute 600 in a
    // dead-quiet room) lets steady noise raise the gate without blocking
    // real speech, which sits well above any room noise floor.
    private var noiseFloor = 0.0
    private var floorInit = false

    override fun reset() {
        pendingCount = 0
        totalFrames = 0
        voicedFrames = 0
        gatedVoicedFrames = 0
        errorFrames = 0
        peakAbs = 0
        noiseFloor = 0.0
        floorInit = false
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
                var sumSq = 0.0
                for (s in pending) {
                    val v = s.toDouble()
                    sumSq += v * v
                    val a = if (s < 0) -s.toInt() else s.toInt()
                    if (a > peakAbs) peakAbs = a
                }
                val rms = kotlin.math.sqrt(sumSq / frameSamples)
                if (!floorInit || rms < noiseFloor) {
                    noiseFloor = rms
                    floorInit = true
                }
                val energyThreshold = maxOf(noiseFloor * ENERGY_FLOOR_FACTOR, MIN_ENERGY_RMS)
                val aboveEnergy = rms >= energyThreshold

                val r = try {
                    WebRtcVadNative.nativeProcess(handle, pending, frameSamples)
                } catch (_: Throwable) { -1 }
                totalFrames++
                if (r == 1) voicedFrames++
                if (r == 1 && aboveEnergy) {
                    speech = true
                    gatedVoicedFrames++
                } else if (r < 0) {
                    errorFrames++
                }
                if (!aboveEnergy) {
                    // Drift floor up toward steady ambient so the gate adapts
                    // to a fan turning on mid-recording.
                    noiseFloor = noiseFloor * 0.97 + rms * 0.03
                }
                pendingCount = 0
            }
        }
        return speech
    }

    override fun diagnostics(): String {
        val base = "WebRTC frame=$frameSamples: voiced $voicedFrames/$totalFrames " +
                "(gated $gatedVoicedFrames), peak $peakAbs/32767, floor ${noiseFloor.toInt()}"
        return if (errorFrames > 0) "$base, errors $errorFrames" else base
    }

    override fun close() {
        if (handle != 0L) {
            try { WebRtcVadNative.nativeFree(handle) } catch (_: Throwable) {}
            handle = 0L
        }
    }

    companion object {
        private const val TAG = "WebRtcVad"

        // Energy-gate tuning: same constants as EnergyVad so behaviour is
        // consistent between methods. A normal voice 30 cm from a phone mic
        // runs RMS 1500-6000; fan/AC rumble runs 200-1200 — so floor*2.5
        // (or the 600 absolute) cleanly separates them once the floor adapts.
        private const val ENERGY_FLOOR_FACTOR = 2.5
        private const val MIN_ENERGY_RMS = 600.0

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

            // Self-test: the WebRTC VAD only accepts 10/20/30 ms frames for the
            // configured rate. On at least one real device, fvad_process rejected
            // the 30 ms frame on *every* call (returning -1), so hands-free heard
            // nothing and just timed out. Probe a silent frame at 30/20/10 ms and
            // keep the first length the detector actually accepts; if none work,
            // free the handle and return null so the caller falls back to energy
            // (which works everywhere) instead of silently erroring forever.
            val candidates = intArrayOf(
                sampleRate / 1000 * 30,
                sampleRate / 1000 * 20,
                sampleRate / 1000 * 10
            )
            var frameSamples = 0
            for (len in candidates) {
                val probe = ShortArray(len)
                val r = try {
                    WebRtcVadNative.nativeProcess(handle, probe, len)
                } catch (t: Throwable) { -1 }
                if (r >= 0) { frameSamples = len; break }
            }
            try { WebRtcVadNative.nativeReset(handle) } catch (_: Throwable) {}

            if (frameSamples == 0) {
                Log.w(TAG, "WebRTC VAD self-test failed (rate=$sampleRate, all frame lengths errored); falling back to energy")
                try { WebRtcVadNative.nativeFree(handle) } catch (_: Throwable) {}
                return null
            }
            Log.i(TAG, "WebRTC VAD ready: rate=$sampleRate frame=$frameSamples")
            return WebRtcVad(handle, frameSamples)
        }
    }
}
