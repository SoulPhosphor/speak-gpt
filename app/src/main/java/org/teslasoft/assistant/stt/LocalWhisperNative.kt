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
 * Bridge to libphosphorwhisper.so. Step 2A of the local-Whisper rollout
 * only exposes [pingNative] so we can confirm the NDK + CMake + JNI
 * pipeline works before whisper.cpp lands in step 2B.
 *
 * The native library is loaded lazily via [ensureLoaded] on the first
 * call rather than from an init block; that lets the rest of the app keep
 * running if the .so is missing for any reason (e.g. stripped on an
 * unsupported ABI) instead of crashing at app startup.
 */
object LocalWhisperNative {

    private const val TAG = "LocalWhisperNative"
    private const val LIB_NAME = "phosphorwhisper"

    @Volatile private var loaded: Boolean = false
    @Volatile private var loadError: Throwable? = null

    @JvmStatic external fun pingNative(): String
    @JvmStatic external fun systemInfoNative(): String

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
