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
 * Bridge to libphosphorwhisper.so.
 *
 * The native library is loaded lazily via [ensureLoaded] on the first
 * call rather than from an init block; that lets the rest of the app keep
 * running if the .so is missing for any reason (e.g. stripped on an
 * unsupported ABI) instead of crashing at app startup.
 *
 * Diagnostic functions ([pingNative], [systemInfoNative]) are kept from
 * step 2A/2B so [LocalWhisperEngine] can sanity-check the library before
 * attempting a model load.
 *
 * Transcription functions:
 *   - [initContextNative]    load a ggml model file → opaque handle (0 fail)
 *   - [releaseContextNative] free an init'd context
 *   - [transcribeNative]     run whisper_full on int16 PCM, return text
 */
object LocalWhisperNative {

    private const val TAG = "LocalWhisperNative"
    private const val LIB_NAME = "phosphorwhisper"

    @Volatile private var loaded: Boolean = false
    @Volatile private var loadError: Throwable? = null

    @JvmStatic external fun pingNative(): String
    @JvmStatic external fun systemInfoNative(): String

    @JvmStatic external fun initContextNative(modelPath: String): Long
    @JvmStatic external fun releaseContextNative(handle: Long)
    @JvmStatic external fun transcribeNative(
        handle: Long,
        pcm16: ShortArray,
        sampleRate: Int,
        language: String
    ): String

    // Cooperative abort for an in-flight transcribeNative call. whisper_full
    // polls a callback between decode steps, so signalling here lets a hung
    // or unwanted transcription stop instead of running to completion on a
    // background thread (where it could otherwise race the next run on the
    // same context).
    @JvmStatic external fun signalAbortNative()
    @JvmStatic external fun clearAbortNative()

    /**
     * Attempts System.loadLibrary once. Subsequent calls are no-ops. Safe
     * to call repeatedly from any thread. Returns true when the library is
     * available for native calls.
     */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (loadError != null) return false

        synchronized(this) {
            if (loaded) return true
            if (loadError != null) return false
            // The shipped arm64-v8a .so is built with armv8.2 dotprod + fp16
            // instructions. On a CPU without those (pre-Cortex-A55/A75
            // arm64), the first native call SIGILLs and the app vanishes
            // with no Java exception. Refuse the load and let callers fall
            // back to cloud STT.
            if (!NativeCpuSupport.isSupported()) {
                loadError = UnsupportedOperationException(
                    "On-device Whisper requires ARMv8.2 dotprod+fp16; not present on this CPU"
                )
                Log.w(TAG, "Skipping lib$LIB_NAME.so load: CPU unsupported")
                return false
            }
            try {
                System.loadLibrary(LIB_NAME)
                loaded = true
                Log.i(TAG, "Loaded lib$LIB_NAME.so")
            } catch (t: Throwable) {
                loadError = t
                Log.w(TAG, "Failed to load lib$LIB_NAME.so", t)
            }
        }
        return loaded
    }

    /**
     * Requests that any in-flight [transcribeNative] call abort as soon as
     * possible. No-op if the library isn't loaded or nothing is running.
     */
    fun signalAbort() {
        if (!loaded) return
        try { signalAbortNative() } catch (t: Throwable) { Log.w(TAG, "signalAbort failed", t) }
    }

    /** Clears a pending abort request before starting a fresh transcription. */
    fun clearAbort() {
        if (!loaded) return
        try { clearAbortNative() } catch (t: Throwable) { Log.w(TAG, "clearAbort failed", t) }
    }

    /** Convenience wrapper: returns the native ping or null on failure. */
    fun safePing(): String? {
        if (!ensureLoaded()) return null
        return try {
            pingNative()
        } catch (t: Throwable) {
            Log.w(TAG, "pingNative call failed", t)
            null
        }
    }

    /**
     * Returns whisper.cpp's system info string (active backends + CPU
     * features). Confirms upstream sources actually compiled in. null on
     * failure for the same reasons as [safePing].
     */
    fun safeSystemInfo(): String? {
        if (!ensureLoaded()) return null
        return try {
            systemInfoNative()
        } catch (t: Throwable) {
            Log.w(TAG, "systemInfoNative call failed", t)
            null
        }
    }
}
