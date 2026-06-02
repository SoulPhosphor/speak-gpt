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
 * Bridge to the libfvad (WebRTC VAD) functions compiled into
 * libphosphorwhisper.so. Loaded lazily and defensively — if the .so or the
 * fvad symbols are missing for any reason, [ensureLoaded] returns false and
 * the caller ([WebRtcVad.create]) falls back to the energy detector instead
 * of crashing.
 *
 *   - [nativeNew]      create a detector for (mode, sampleRate) → handle (0 fail)
 *   - [nativeReset]    clear detector state between recordings
 *   - [nativeProcess]  classify one 10/20/30 ms frame → 1 voice / 0 not / -1 err
 *   - [nativeFree]     release the detector
 */
object WebRtcVadNative {

    private const val TAG = "WebRtcVadNative"
    private const val LIB_NAME = "phosphorwhisper"

    @Volatile private var loaded: Boolean = false
    @Volatile private var loadError: Throwable? = null

    @JvmStatic external fun nativeNew(mode: Int, sampleRate: Int): Long
    @JvmStatic external fun nativeReset(handle: Long, mode: Int, sampleRate: Int)
    @JvmStatic external fun nativeProcess(handle: Long, frame: ShortArray, length: Int): Int
    @JvmStatic external fun nativeFree(handle: Long)

    /**
     * Attempts System.loadLibrary once. The native library is shared with
     * [LocalWhisperNative]; loading it twice by name is a harmless no-op.
     * Returns true when WebRTC VAD calls are safe to make.
     */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (loadError != null) return false
        synchronized(this) {
            if (loaded) return true
            if (loadError != null) return false
            // Same CPU gate as LocalWhisperNative: libphosphorwhisper.so is
            // built with armv8.2 dotprod+fp16 instructions. Refuse the load
            // on pre-A55/A75 arm64 CPUs so the energy-VAD fallback runs
            // instead of the app crashing on first nativeProcess call.
            if (!NativeCpuSupport.isSupported()) {
                loadError = UnsupportedOperationException(
                    "WebRTC VAD requires ARMv8.2 dotprod+fp16; not present on this CPU"
                )
                Log.w(TAG, "Skipping lib$LIB_NAME.so load: CPU unsupported")
                return false
            }
            try {
                System.loadLibrary(LIB_NAME)
                loaded = true
                Log.i(TAG, "Loaded lib$LIB_NAME.so for WebRTC VAD")
            } catch (t: Throwable) {
                loadError = t
                Log.w(TAG, "Failed to load lib$LIB_NAME.so for WebRTC VAD", t)
            }
        }
        return loaded
    }
}
