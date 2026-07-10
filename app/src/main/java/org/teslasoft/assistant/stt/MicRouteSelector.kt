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

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Decides which microphone the app should capture from and reports, in plain
 * words, which one is actually live.
 *
 * The rule the owner asked for: **if a Bluetooth headset is connected, use it;
 * otherwise use the built-in mic** — re-evaluated every time the mic opens, so
 * a headset that connects or drops mid-conversation is picked up on the next
 * listening turn without the user doing anything.
 *
 * Why this is needed at all: opening an [android.media.AudioRecord] with
 * `AudioSource.MIC` does NOT, on its own, capture from a Bluetooth headset's
 * microphone — Android only routes capture to a Bluetooth SCO mic when the app
 * explicitly selects it as the communication device. Without this, the app
 * silently records from the phone's built-in mic even while a headset is worn
 * (which is exactly what the Audio Health log was correctly reporting as
 * "built-in mic"). [setCommunicationDevice] is the modern (Android 12+) way to
 * make that choice; it also starts/owns the SCO link.
 *
 * This object is the single place that knows about routing so the fragile
 * capture engine stays readable, and so the same device labels are used by the
 * mic-open log line and the Audio Health summary.
 */
object MicRouteSelector {

    /**
     * Outcome of selecting the input for one recording.
     *
     * @param requested the device we asked the OS to capture from (a Bluetooth
     *   SCO headset), or null when we left it on the built-in/default mic. The
     *   engine also points the AudioRecord at this via `preferredDevice`.
     * @param requestedLabel human-readable form of [requested] for the log.
     * @param beforeLabel the active communication input just before the mic
     *   opened (best-effort — there is no live capture device until the mic
     *   is open, so this reflects the current OS routing).
     * @param bluetoothAvailable whether a Bluetooth headset was connected.
     */
    data class Result(
        val requested: AudioDeviceInfo?,
        val requestedLabel: String,
        val beforeLabel: String,
        val bluetoothAvailable: Boolean
    )

    /**
     * Picks the preferred input for the next recording and, on Android 12+,
     * actually routes capture there. Bluetooth headset wins when present; with
     * no headset we explicitly clear any routing so a headset that has since
     * disconnected falls back to the built-in mic. Never throws — routing is a
     * best-effort nicety and must not break capture.
     */
    fun selectPreferredInput(context: Context): Result {
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return Result(null, "built-in mic", "unavailable", false)

        // Bluetooth SCO capture routing via the communication-device API needs
        // Android 12 (API 31). Below that we leave the OS default (built-in)
        // and only report it; the owner's device is well above this floor.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return Result(null, "built-in mic", "system default (Bluetooth routing needs Android 12+)", false)
        }

        val current = try { am.communicationDevice } catch (_: Throwable) { null }
        val before = label(current)
        val bt = try {
            am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        } catch (_: Throwable) { null }

        return if (bt != null) {
            // Only switch when we aren't already on this headset. Re-selecting
            // the SAME device every turn forced the OS to renegotiate the SCO
            // link at the exact moment the mic opened — a dead-air gap that ate
            // the start of the user's speech and burned the no-speech window
            // against a link that wasn't live yet ("I start talking and it
            // records none of it", "switching to Bluetooth even though it's
            // already Bluetooth" — owner report, July 10 2026). A headset that
            // connects or drops mid-conversation is still honoured: this
            // re-evaluates every turn, it just no-ops when nothing changed.
            val alreadyRouted = current != null &&
                    current.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && current.id == bt.id
            val ok = if (alreadyRouted) true
                     else try { am.setCommunicationDevice(bt) } catch (_: Throwable) { false }
            // If the OS refused the switch, capture stays on the built-in mic;
            // say so rather than claiming the headset is in use.
            Result(if (ok) bt else null, label(bt) + if (ok) "" else " (selection failed)", before, true)
        } else {
            // No headset connected: undo any SCO selection from an earlier turn
            // whose headset has since dropped, so we return to the built-in mic.
            // (Only if one is actually set — a blind clear also churns routing.)
            if (current != null) {
                try { am.clearCommunicationDevice() } catch (_: Throwable) {}
            }
            Result(null, "built-in mic", before, false)
        }
    }

    /**
     * Releases any communication-device routing taken for capture, returning
     * input (and the Bluetooth SCO link) to the OS default. Safe to call when
     * nothing was routed. Call this when the listening loop ends so a headset
     * isn't left in call/SCO mode after the user stops talking to the app.
     */
    fun clear(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try { am.clearCommunicationDevice() } catch (_: Throwable) {}
    }

    /**
     * Plain-words name for an audio device, shared by the mic-open log line and
     * the Audio Health summary so they always agree. Appends the device's own
     * product name when it adds information (e.g. the headset's name), which
     * makes "which mic" unambiguous in the event log.
     */
    fun label(dev: AudioDeviceInfo?): String {
        if (dev == null) return "unavailable"
        val base = when (dev.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in mic"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "built-in earpiece"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "built-in speaker"
            else -> "type ${dev.type}"
        }
        val name = try { dev.productName?.toString()?.trim() } catch (_: Throwable) { null }
        return if (!name.isNullOrEmpty() && !base.contains(name, ignoreCase = true)) "$base ($name)" else base
    }
}
