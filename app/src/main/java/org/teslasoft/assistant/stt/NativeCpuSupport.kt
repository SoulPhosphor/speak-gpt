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

import android.os.Build
import android.util.Log
import java.io.File

/**
 * Whether the CPU has the ARMv8.2-A extensions that libphosphorwhisper.so
 * is compiled against (dotprod + fp16, per app/src/main/cpp/CMakeLists.txt).
 *
 * The shipped arm64-v8a build hard-bakes `udot`/`sdot` and fp16 arithmetic
 * into ggml's quantized matmul kernels. On a pre-Cortex-A55/A75 arm64 CPU
 * those instructions don't exist; the first time native code hits one the
 * process gets SIGILL — unrecoverable, no Java exception, the app just
 * vanishes. Gate the library load on the CPU actually having the features
 * so unsupported devices fall back to the cloud STT path instead of
 * crashing.
 *
 * Non-arm64 ABIs (x86_64 emulator builds, armeabi-v7a) don't carry the
 * GGML ARM arch flags, so they're reported supported. /proc/cpuinfo is the
 * source of truth: it's world-readable on Android and lists CPU features
 * in a stable `Features:` line.
 */
object NativeCpuSupport {

    private const val TAG = "NativeCpuSupport"

    @Volatile private var cached: Boolean? = null

    /** True iff the current CPU has the extensions the .so requires. */
    fun isSupported(): Boolean {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val result = check()
            cached = result
            return result
        }
    }

    private fun check(): Boolean {
        val abi = Build.SUPPORTED_ABIS.firstOrNull()
        if (abi != "arm64-v8a") return true
        return try {
            var features = ""
            File("/proc/cpuinfo").bufferedReader().use { reader ->
                for (line in reader.lineSequence()) {
                    if (line.startsWith("Features")) {
                        features = line
                        break
                    }
                }
            }
            val tokens = features
                .substringAfter(":", "")
                .trim()
                .split("\\s+".toRegex())
                .toHashSet()
            // asimddp = FEAT_DotProd (udot/sdot); fphp = FEAT_FP16 scalar;
            // asimdhp = NEON half-precision. ggml's fp16 kernels need at
            // least asimdhp, so accept either fphp or asimdhp as the fp16 bit.
            val hasDotprod = "asimddp" in tokens
            val hasFp16 = "asimdhp" in tokens || "fphp" in tokens
            val ok = hasDotprod && hasFp16
            if (!ok) {
                Log.w(TAG, "CPU lacks ARMv8.2 dotprod/fp16; on-device Whisper disabled. " +
                        "features=[$features]")
            }
            ok
        } catch (t: Throwable) {
            // /proc/cpuinfo unreadable is unusual on Android; fall through
            // to "supported" rather than locking every user out from a
            // misread. The native side will crash on the unsupported subset
            // — same blast radius as before this gate existed.
            Log.w(TAG, "Could not read /proc/cpuinfo; assuming supported", t)
            true
        }
    }
}
