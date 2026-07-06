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

package org.teslasoft.assistant.util

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import java.io.File

/**
 * Shared memory-footprint sampler for the two opt-in performance diagnostics
 * (Whisper performance logging + Memory usage logging, both in Alerts, Errors &
 * Logs, both off by default, both writing to the Performance Log).
 *
 * Two shapes:
 *  - [snapshotCompact] — a short one-liner tail appended to each Whisper timing
 *    line, so a slow transcription can be read next to the memory state at that
 *    instant without a second lookup.
 *  - [snapshotFull] — the richer line the ~60s memory heartbeat writes; the
 *    extra fields (PSS, thread count) are the ones that actually reveal a leak
 *    growing over time.
 *
 * Everything here is best-effort and self-guarded: a diagnostic must never
 * throw into the caller. Numbers are reported in MB (1 MB = 1024*1024 bytes),
 * rounded, so the log stays readable at a glance.
 */
object MemoryDiagnostics {

    // Process start, captured the first time this object is touched (app start,
    // via MainApplication). Lets every sample carry "minutes into this process"
    // so a growth curve can be plotted from the log without external timestamps.
    private val processStartMs: Long = SystemClock.elapsedRealtime()

    /** Minutes since this process started (best-effort, monotonic). */
    fun processUptimeMinutes(): Long =
        (SystemClock.elapsedRealtime() - processStartMs) / 60_000L

    private fun mb(bytes: Long): Long = (bytes + (512L * 1024L)) / (1024L * 1024L)

    /**
     * Compact footprint for the Whisper timing tail:
     * `heap=NN/NNMB native=NNMB avail=NNMB low=false`.
     * Java heap used/max, native heap allocated, system available memory, and
     * the system low-memory flag — the cheapest fields that still answer "was
     * the phone under memory pressure when this decode was slow?".
     */
    fun snapshotCompact(context: Context): String {
        return try {
            val rt = Runtime.getRuntime()
            val javaUsed = mb(rt.totalMemory() - rt.freeMemory())
            val javaMax = mb(rt.maxMemory())
            val nativeAlloc = mb(Debug.getNativeHeapAllocatedSize())
            val mi = systemMemoryInfo(context)
            val availStr = mi?.let { "${mb(it.availMem)}MB" } ?: "?"
            val lowStr = mi?.lowMemory?.toString() ?: "?"
            "heap=$javaUsed/${javaMax}MB native=${nativeAlloc}MB avail=$availStr low=$lowStr"
        } catch (_: Throwable) {
            "mem=unavailable"
        }
    }

    /**
     * Full footprint for the memory heartbeat. Adds the fields that matter for
     * leak hunting: total PSS (the real "how much memory is this process using"
     * number) and live thread count (a thread leak is a common Android leak),
     * plus native heap size/free and the system memory threshold.
     */
    fun snapshotFull(context: Context): String {
        return try {
            val rt = Runtime.getRuntime()
            val javaUsed = mb(rt.totalMemory() - rt.freeMemory())
            val javaTotal = mb(rt.totalMemory())
            val javaMax = mb(rt.maxMemory())
            val nativeAlloc = mb(Debug.getNativeHeapAllocatedSize())
            val nativeSize = mb(Debug.getNativeHeapSize())

            // Debug.getMemoryInfo fills PSS without the ActivityManager round-trip;
            // getTotalPss() is in KB. Cheap enough for a 60s cadence.
            val pssStr = try {
                val dmi = Debug.MemoryInfo()
                Debug.getMemoryInfo(dmi)
                "${(dmi.totalPss + 512) / 1024}MB"
            } catch (_: Throwable) { "?" }

            val mi = systemMemoryInfo(context)
            val availStr = mi?.let { "${mb(it.availMem)}MB" } ?: "?"
            val thresholdStr = mi?.let { "${mb(it.threshold)}MB" } ?: "?"
            val lowStr = mi?.lowMemory?.toString() ?: "?"

            "uptime=${processUptimeMinutes()}m pss=$pssStr heap=$javaUsed/$javaTotal/${javaMax}MB " +
                "native=$nativeAlloc/${nativeSize}MB threads=${threadCount()} " +
                "avail=$availStr low=$lowStr lowThreshold=$thresholdStr"
        } catch (_: Throwable) {
            "mem=unavailable"
        }
    }

    private fun systemMemoryInfo(context: Context): ActivityManager.MemoryInfo? {
        return try {
            val am = context.applicationContext
                .getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Live OS-thread count for this process, read from /proc/self/status
     * ("Threads:"). Thread.activeCount() only counts the caller's thread group,
     * which badly undercounts a real app; the /proc value is the true total and
     * is what exposes a thread leak. Falls back to Thread.activeCount() if /proc
     * is unreadable.
     */
    private fun threadCount(): Int {
        try {
            val threadsLine = File("/proc/self/status").useLines { seq ->
                seq.firstOrNull { it.startsWith("Threads:") }
            }
            val parsed = threadsLine?.substringAfter("Threads:")?.trim()?.toIntOrNull()
            if (parsed != null) return parsed
        } catch (_: Throwable) { /* fall through to the JVM approximation */ }
        return try { Thread.activeCount() } catch (_: Throwable) { -1 }
    }
}
