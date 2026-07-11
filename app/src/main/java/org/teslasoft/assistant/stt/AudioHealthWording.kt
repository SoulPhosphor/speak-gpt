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

/**
 * Wording selection for the Audio Health diagnostic's built-in-mic →
 * Bluetooth-SCO "warm-up" case, pulled out of [AudioHealthMonitor] so the
 * choice is unit-testable (owner ruling, July 11 2026): a HEALTHY warm-up —
 * the routine per-turn event where the headset's mic link comes up a moment
 * after the mic opens and the audio was fine — must read as one factual
 * line, not an incident explanation with hypothetical troubleshooting.
 * The problem wording (used only when the same route pattern occurred but
 * the audio was NOT healthy) is unchanged in this pass.
 */
object AudioHealthWording {

    /** The healthy warm-up line (owner-approved words, July 11 2026). */
    const val HEALTHY_BLUETOOTH_WARM_UP =
        "Bluetooth microphone connected shortly after recording began; audio remained healthy."

    /** The pre-existing warm-up paragraph, now shown ONLY when the warm-up
     *  turn was not healthy (kept verbatim; separate review may reword it). */
    const val WARM_UP_WITH_UNHEALTHY_AUDIO =
        "The recording started on the phone's microphone and switched to your Bluetooth headset a moment later. " +
        "This is normal Bluetooth 'warm-up' — the headset's mic link takes a beat to connect after the mic opens — " +
        "NOT your headset dropping or you disconnecting anything. Only the very start was on the phone mic; " +
        "the rest was the headset. Nothing to fix; if the first word ever gets clipped, pause briefly before " +
        "speaking after the mic opens."

    /**
     * Picks the warm-up hint. Callers must have already established that the
     * warm-up route pattern occurred (initial input was NOT Bluetooth SCO and
     * the current input IS Bluetooth SCO — never a drop, which is the opposite
     * transition and keeps its own wording).
     *
     * "Healthy" here is the same standard the monitor uses everywhere else:
     * [inputHealthy] = frames arrived, the peak was clearly audible, and
     * fewer than half the frames were near-zero — plus zero clipped frames.
     * Any failed check falls back to the pre-existing problem wording, so the
     * concise line can never appear on a turn where Bluetooth actually
     * misbehaved or the audio was bad.
     */
    fun bluetoothWarmUpHint(inputHealthy: Boolean, clippedFrames: Long): String =
        if (inputHealthy && clippedFrames == 0L) HEALTHY_BLUETOOTH_WARM_UP
        else WARM_UP_WITH_UNHEALTHY_AUDIO
}
