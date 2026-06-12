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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Energy detector's hysteresis (two-level gate) and
 * speech-hold. A constant-amplitude frame has RMS equal to its amplitude,
 * which makes the gate math exact: with a quiet calibration frame (RMS 100)
 * the floor snapshots to 100 and the enter gate is max(100*2.5, 600) = 600;
 * the default exit ratio of 0.5 puts the exit gate at 300.
 */
class EnergyVadHysteresisTest {

    private fun frame(amplitude: Int, samples: Int = 320): ShortArray =
        ShortArray(samples) { amplitude.toShort() }

    private fun vad(
        hysteresis: Boolean = true,
        exitRatio: Double = 0.5,
        hangoverMs: Long = 0L
    ) = EnergyVad(
        VadTuning(
            hysteresisEnabled = hysteresis,
            hysteresisExitRatio = exitRatio,
            hangoverMs = hangoverMs
        ),
        16_000
    )

    private fun VoiceActivityDetector.hear(amplitude: Int, samples: Int = 320): Boolean =
        accept(frame(amplitude, samples), samples)

    @Test fun quietFramesStaySilent() {
        val v = vad()
        assertFalse(v.hear(100))
        assertFalse(v.hear(150))
    }

    @Test fun loudFrameEntersSpeech() {
        val v = vad()
        v.hear(100) // calibrate floor
        assertTrue(v.hear(800))
    }

    @Test fun hysteresisKeepsQuieterWordsInSpeech() {
        val v = vad(hysteresis = true)
        v.hear(100)              // floor -> 100, enter gate 600, exit 300
        assertTrue(v.hear(800))  // crosses enter
        // 400 is below the enter gate but above the exit gate — the exact
        // "quieter word mid-sentence" that used to read as silence.
        assertTrue(v.hear(400))
        assertTrue(v.hear(350))
    }

    @Test fun withoutHysteresisQuieterWordsReadAsSilence() {
        val v = vad(hysteresis = false)
        v.hear(100)
        assertTrue(v.hear(800))
        assertFalse(v.hear(400))
    }

    @Test fun speechExitsBelowExitGateAndMustReenterAtFullGate() {
        val v = vad(hysteresis = true)
        v.hear(100)
        assertTrue(v.hear(800))
        assertFalse(v.hear(200)) // below exit (300): speech state drops
        // Back at 400: between exit and enter, but we are no longer in
        // speech, so the full enter gate (600) applies again.
        assertFalse(v.hear(400))
        assertTrue(v.hear(700))  // re-crossing enter works
    }

    @Test fun hangoverHoldsBriefDipsAsSpeech() {
        // 100 ms hold at 16 kHz = 1600 samples; quiet frames of 800 samples
        // are 50 ms each, so two are held and the third is not.
        val v = vad(hangoverMs = 100L)
        v.hear(100)
        assertTrue(v.hear(800))
        assertTrue(v.hear(100, samples = 800))
        assertTrue(v.hear(100, samples = 800))
        assertFalse(v.hear(100, samples = 800))
    }

    @Test fun hangoverOffByDefault() {
        val v = vad()
        v.hear(100)
        assertTrue(v.hear(800))
        assertFalse(v.hear(100, samples = 800))
    }

    @Test fun diagnosticsReportHysteresisState() {
        val on = vad(hysteresis = true)
        on.hear(100)
        on.hear(800)
        on.hear(400)
        assertTrue(on.diagnostics().contains("hyst exit"))

        val off = vad(hysteresis = false)
        off.hear(100)
        assertTrue(off.diagnostics().contains("hyst off"))
    }

    @Test fun resetClearsHysteresisState() {
        val v = vad(hysteresis = true)
        v.hear(100)
        assertTrue(v.hear(800))
        v.reset()
        v.hear(100)              // recalibrate after reset
        assertFalse(v.hear(400)) // no lingering in-speech state from before
    }
}
