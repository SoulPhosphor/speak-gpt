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

package org.teslasoft.assistant.preferences

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class Logger {
    companion object {
        /**
         * Persistent voice/process-exit diagnostics must never make the UI wait
         * on Android Keystore. A single worker also preserves their enqueue
         * order; using one new Thread per line would let encrypted read/modify/
         * write operations race and overwrite one another.
         *
         * CrashHandler continues to call [log] directly: its dedicated crash
         * screen needs the write to finish before the process can be closed.
         */
        private val backgroundLogWriter = Executors.newSingleThreadExecutor { task ->
            Thread(task, "encrypted-log-writer")
        }

        /**
         * Get crash log
         * */
        fun getCrashLog(context: Context) : String {
            return EncryptedPreferences.getEncryptedPreference(context, "logs", "crash")
        }

        /**
         * Set crash log
         * */
        private fun setCrashLog(context: Context, log: String) {
            EncryptedPreferences.setEncryptedPreference(context, "logs", "crash", log)
        }

        /**
         * Clear crash log
         * */
        fun clearCrashLog(context: Context) {
            setCrashLog(context, "")
        }

        /**
         * Get event log
         * */
        fun getEventLog(context: Context) : String {
            return EncryptedPreferences.getEncryptedPreference(context, "logs", "event")
        }

        /**
         * Set event log
         * */
        private fun setEventLog(context: Context, log: String) {
            EncryptedPreferences.setEncryptedPreference(context, "logs", "event", log)
        }

        /**
         * Clear event log
         * */
        fun clearEventLog(context: Context) {
            setEventLog(context, "")
        }

        /**
         * Memory Debug Log — the companion memory system's own diagnostics
         * channel, separate from the Voice Debug (event) log so the two never
         * mix. Written only when "Memory diagnostics logging" is enabled.
         * */
        fun getMemoryLog(context: Context) : String {
            return EncryptedPreferences.getEncryptedPreference(context, "logs", "memory")
        }

        private fun setMemoryLog(context: Context, log: String) {
            EncryptedPreferences.setEncryptedPreference(context, "logs", "memory", log)
        }

        fun clearMemoryLog(context: Context) {
            setMemoryLog(context, "")
        }

        /**
         * Performance Log — a dedicated channel for the two opt-in performance
         * diagnostics (both off by default, both in Alerts, Errors & Logs):
         * Whisper transcription timing and the app-wide memory-usage heartbeat.
         * Kept separate from the Error Log and the Voice Debug Log on purpose:
         * these are high-volume and only written while actively investigating a
         * slowdown or a suspected RAM leak, so they must not evict real crash
         * entries or bury the per-turn voice trail. Timing lines and memory
         * samples share this one channel so they can be read side by side (a
         * decode spike next to the memory state at that instant).
         * */
        fun getPerformanceLog(context: Context) : String {
            return EncryptedPreferences.getEncryptedPreference(context, "logs", "performance")
        }

        private fun setPerformanceLog(context: Context, log: String) {
            EncryptedPreferences.setEncryptedPreference(context, "logs", "performance", log)
        }

        fun clearPerformanceLog(context: Context) {
            setPerformanceLog(context, "")
        }

        /**
         * The persistent log channels [log] recognizes. A `type` outside this
         * set is silently dropped (see the unknown-channel `else` in [log]),
         * so a caller passing e.g. "error" instead of "crash" writes nowhere.
         * Exposed so callers/tests can verify a channel name is real before
         * relying on it reaching a durable log. Kept in sync with the `when`
         * in [log].
         */
        fun isPersistentType(type: String): Boolean =
            type == "crash" || type == "event" || type == "memory" || type == "performance"

        /**
         * @param type - type of log (crash/event/memory/performance)
         * @param tag - any tag to identify log message and source
         * @param level - log level (info/error/warning/debug/verbose)
         * @param message - log message
         * */
        fun log(context: Context, type: String, tag: String, level: String, message: String) {
            // These logs never leave the device in this fork (the upstream
            // TeslaSoft telemetry was removed); they exist solely so the user
            // can read them in Settings -> Event log. The old guard skipped
            // logging entirely when the installation id was zeroed (telemetry
            // consent revoked), which silently ate the user's own diagnostics
            // — the "I turned logging on and the event log stayed empty" bug.
            // A diagnostic logging call must NEVER crash the app. This used to
            // `error("Invalid log level")` / `error("Invalid log type")`, which
            // turned a mistyped level (e.g. "ERROR" instead of "error") into a
            // fatal IllegalStateException — a logging helper taking down the
            // process. Now the level is case-normalized and anything
            // unrecognized is dropped silently.
            val lvl = level.lowercase()
            if (lvl != "info" && lvl != "error" && lvl != "warning" && lvl != "debug" && lvl != "verbose") return

            // Local time, second precision. The ISO/UTC instant this used
            // to print ("2026-06-12T19:03:31.903759Z") was unreadable on a
            // phone and in the wrong timezone, which made correlating a
            // log line with "the turn that just failed" impossible.
            val timestamp = LocalDateTime.now().format(LOG_TIME_FORMAT)
            val logString =
                "[$timestamp] [$tag] [${lvl.uppercase()}] $message\n"
            when (type) {
                // The "crash" channel is the user-facing Error Log (app crashes
                // plus all generation/handled GenError entries); the "event"
                // channel is the Voice Debug Log. Storage keys are kept as-is so
                // existing logs survive; only the labels and retention differ.
                "crash" -> {
                    val log = trimByEntries(
                        "${getCrashLog(context)}$logString", ERROR_LOG_MAX_ENTRIES, ERROR_LOG_MAX_AGE_DAYS
                    )
                    setCrashLog(context, log)
                }

                "event" -> {
                    val log = trimByEntries(
                        "${getEventLog(context)}$logString", VOICE_LOG_MAX_ENTRIES, VOICE_LOG_MAX_AGE_DAYS
                    )
                    setEventLog(context, log)
                }

                "memory" -> {
                    val log = trimByEntries(
                        "${getMemoryLog(context)}$logString", MEMORY_LOG_MAX_ENTRIES, MEMORY_LOG_MAX_AGE_DAYS
                    )
                    setMemoryLog(context, log)
                }

                "performance" -> {
                    val log = trimByEntries(
                        "${getPerformanceLog(context)}$logString", PERF_LOG_MAX_ENTRIES, PERF_LOG_MAX_AGE_DAYS
                    )
                    setPerformanceLog(context, log)
                }

                // Unknown channel: drop silently rather than crash.
                else -> return
            }
        }

        /**
         * Enqueue a persistent diagnostic without making the caller (often a
         * microphone callback on the main thread) wait for Keystore or disk.
         * Best-effort by design, matching [log]'s existing diagnostic contract.
         */
        fun logAsync(context: Context, type: String, tag: String, level: String, message: String) {
            val appContext = context.applicationContext
            backgroundLogWriter.execute {
                try {
                    log(appContext, type, tag, level, message)
                } catch (_: Throwable) { /* diagnostics must never disturb the caller */ }
            }
        }

        private val LOG_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        // Per-log retention (see ERROR_CODES.md section 4.2). Voice diagnostics are
        // far higher volume, so the Voice Debug Log keeps more entries over a
        // shorter window. Each limit is independent; whichever is hit first wins.
        private const val ERROR_LOG_MAX_ENTRIES = 500
        private const val ERROR_LOG_MAX_AGE_DAYS = 30L
        private const val VOICE_LOG_MAX_ENTRIES = 1000
        private const val VOICE_LOG_MAX_AGE_DAYS = 7L
        private const val MEMORY_LOG_MAX_ENTRIES = 1000
        private const val MEMORY_LOG_MAX_AGE_DAYS = 7L
        // The performance channel is the highest-volume of all (a memory sample
        // every ~60s plus a line per transcription), so it keeps the most
        // entries over the same short window as the voice log.
        private const val PERF_LOG_MAX_ENTRIES = 2000
        private const val PERF_LOG_MAX_AGE_DAYS = 7L

        // Safety cap used only if a log somehow contains no recognizable entry
        // headers (it never should — every line we write starts with one).
        private const val FALLBACK_MAX_CHARS = 200_000

        // A new entry begins at a line starting with the "[yyyy-MM-dd HH:mm:ss] "
        // header Logger writes; everything up to the next such line (e.g. a
        // multi-line stack trace) belongs to the same entry.
        private val ENTRY_HEADER =
            Regex("""(?m)^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})] """)

        /**
         * Trim a stored log by **whole entries** — never by physical lines — so a
         * multi-line stack trace or GenError block is kept or dropped as a unit and
         * is never cut in half (the bug the old character-count trim could cause).
         * Drops entries older than [maxAgeDays], then, if still over [maxEntries],
         * drops the oldest until the count fits. `internal` for unit testing.
         */
        internal fun trimByEntries(log: String, maxEntries: Int, maxAgeDays: Long): String {
            if (log.isEmpty()) return log
            val headers = ENTRY_HEADER.findAll(log).toList()
            if (headers.isEmpty()) {
                // Unrecognizable content: fall back to a char cap so it can't grow
                // without bound.
                return if (log.length > FALLBACK_MAX_CHARS) log.takeLast(FALLBACK_MAX_CHARS) else log
            }

            data class Entry(val timestamp: LocalDateTime?, val text: String)
            val entries = ArrayList<Entry>(headers.size)
            for (i in headers.indices) {
                val start = headers[i].range.first
                val end = if (i + 1 < headers.size) headers[i + 1].range.first else log.length
                val ts = try {
                    LocalDateTime.parse(headers[i].groupValues[1], LOG_TIME_FORMAT)
                } catch (_: Exception) {
                    null
                }
                entries.add(Entry(ts, log.substring(start, end)))
            }

            val cutoff = LocalDateTime.now().minusDays(maxAgeDays)
            // Keep entries newer than the cutoff; an unparseable timestamp is kept
            // rather than guessed away.
            var kept = entries.filter { it.timestamp == null || !it.timestamp.isBefore(cutoff) }
            if (kept.size > maxEntries) {
                kept = kept.subList(kept.size - maxEntries, kept.size)
            }
            return kept.joinToString("") { it.text }
        }

        /**
         * Surface *why the previous process died* into the Event (Voice Debug)
         * log on the next start. A hard kill — low memory, force-stop — is a
         * SIGKILL: no app code runs as the process dies, so it can never write a
         * tombstone on the way out the way a normal onDestroy can. The only way to
         * make that visible is to ask the system after the fact, which
         * ActivityManager.getHistoricalProcessExitReasons does (API 30+). This is
         * exactly the "the readback just stopped and nothing in the log says why"
         * case: now the next launch records the real cause.
         *
         * Deduped by the exit timestamp (stored in the logs prefs) so the same
         * death is logged once, not on every cold start. Best-effort and fully
         * guarded — diagnostics must never crash startup. Written unconditionally
         * (not gated on the voice-diagnostics toggle): it's one line per process
         * death and is the whole point of asking "was I killed?".
         */
        fun logLastExitReason(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            val appContext = context.applicationContext
            backgroundLogWriter.execute {
                try {
                    collectAndLogLastExitReason(appContext)
                } catch (_: Throwable) { /* never let diagnostics crash startup */ }
            }
        }

        private const val LAST_EXIT_TS_KEY = "last_exit_ts"

        @RequiresApi(Build.VERSION_CODES.R)
        private fun collectAndLogLastExitReason(context: Context) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 1)
            if (reasons.isEmpty()) return
            val info = reasons[0]

            // Log each death once. The most recent exit stays the same across cold
            // starts until a new one happens, so dedup on its timestamp.
            val ts = info.timestamp.toString()
            val lastSeen = EncryptedPreferences.getEncryptedPreference(context, "logs", LAST_EXIT_TS_KEY)
            if (lastSeen == ts) return
            EncryptedPreferences.setEncryptedPreference(context, "logs", LAST_EXIT_TS_KEY, ts)

            val whenStr = LocalDateTime
                .ofInstant(Instant.ofEpochMilli(info.timestamp), ZoneId.systemDefault())
                .format(LOG_TIME_FORMAT)
            val detail = info.description?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
            val message = "previous app session ended at $whenStr: ${describeExitReason(info.reason)}$detail"

            // Low-memory / signaled / crash deaths are the ones that silently cut
            // off a readback; flag them as warnings so they stand out from a clean
            // exit in the log.
            val level = when (info.reason) {
                ApplicationExitInfo.REASON_LOW_MEMORY,
                ApplicationExitInfo.REASON_SIGNALED,
                ApplicationExitInfo.REASON_CRASH,
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR,
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "warning"
                else -> "info"
            }
            log(context, "event", "ProcessExit", level, message)

            // A crash or serious freeze that makes the app unusable must ALSO
            // leave a record in the Error Log (the "crash" channel) — where a
            // user looks after "the app just died" — not only in the Voice
            // Debug log above. A hard kill (an ANR = frozen main thread, a
            // native crash, an out-of-memory kill) runs no app code on the way
            // out and throws no JVM exception, so CrashHandler never fires and
            // nothing reaches the Error Log at the time. This after-the-fact
            // report, read on the next launch, is the only place that can put
            // it there. Ordinary JVM-exception crashes (REASON_CRASH) are
            // deliberately NOT copied here — CrashHandler already writes those
            // with a full stack trace, so duplicating them would only bury the
            // Error Log.
            if (isAppBreakingExit(info.reason)) {
                val errorLevel = when (info.reason) {
                    // Memory / resource kills are environmental, not code
                    // faults, so they are warnings; a freeze or native crash
                    // is an error.
                    ApplicationExitInfo.REASON_LOW_MEMORY,
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "warning"
                    else -> "error"
                }
                val trace = readMainThreadTrace(info)
                val errorMessage = if (trace != null) "$message\nMain thread at termination:\n$trace" else message
                log(context, "crash", "ProcessExit", errorLevel, errorMessage)
            }
        }

        /**
         * The abnormal, app-breaking exit reasons that must also reach the Error
         * Log. A hard system kill runs no app code and throws no exception, so
         * these would otherwise leave the Error Log empty. REASON_CRASH (an
         * ordinary JVM exception) is excluded on purpose — CrashHandler already
         * records it there with a full stack trace, so copying it would only
         * duplicate. Normal or user-initiated exits (clean exit, force-stop,
         * user stop, dependency died, freezer) never reach the Error Log.
         */
        @RequiresApi(Build.VERSION_CODES.R)
        private fun isAppBreakingExit(reason: Int): Boolean = when (reason) {
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> true
            else -> false
        }

        // Trace excerpt limits. The frozen main thread's stack is the single
        // most useful piece of an ANR report; we keep ONLY that thread's
        // section (approved scope: main thread, never a full all-thread dump)
        // and cap it so a large report can neither flood the Error Log nor slow
        // the one-time read on the launch after a death.
        private const val TRACE_MAX_LINES = 60
        private const val TRACE_MAX_CHARS = 6000
        // The main thread is first in an ANR dump, so this scan bound is ample
        // while keeping the startup read cheap and finite.
        private const val TRACE_SCAN_MAX_LINES = 400

        /**
         * The main thread's stack from the system's saved report for this exit,
         * if one exists. Android keeps an ANR thread dump (and a native-crash
         * tombstone) reachable via [ApplicationExitInfo.getTraceInputStream];
         * for most other reasons it is null, so the Error Log entry then carries
         * just the reason and description. Best-effort — any failure omits the
         * trace and never disturbs startup. Only ever called after a deduped
         * abnormal exit, so the read happens at most once per death, not on a
         * normal launch.
         */
        @RequiresApi(Build.VERSION_CODES.R)
        private fun readMainThreadTrace(info: ApplicationExitInfo): String? {
            return try {
                val lines = ArrayList<String>(TRACE_SCAN_MAX_LINES)
                info.traceInputStream?.bufferedReader()?.use { reader ->
                    var line = reader.readLine()
                    var scanned = 0
                    while (line != null && scanned < TRACE_SCAN_MAX_LINES) {
                        lines.add(line)
                        line = reader.readLine()
                        scanned++
                    }
                } ?: return null
                extractMainThread(lines)
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * Pull just the main thread's block out of a thread dump: the line that
         * names the "main" thread through to the blank line or the next thread
         * header. Falls back to a capped head of the dump if the main thread
         * cannot be located (still bounded, never the whole all-thread dump).
         * Every kept line is indented two spaces so none can accidentally match
         * the "[yyyy-MM-dd HH:mm:ss] " entry header and split this record when
         * the log is later trimmed by [trimByEntries].
         */
        private fun extractMainThread(lines: List<String>): String? {
            if (lines.isEmpty()) return null
            val startIdx = lines.indexOfFirst { it.trimStart().startsWith("\"main\"") }
            val section: List<String> = if (startIdx >= 0) {
                val out = ArrayList<String>()
                out.add(lines[startIdx])
                var i = startIdx + 1
                while (i < lines.size) {
                    val l = lines[i]
                    if (l.isBlank()) break
                    // The next thread header (e.g. "Signal Catcher" prio=5 ...).
                    if (l.trimStart().startsWith("\"") && l.contains("prio=")) break
                    out.add(l)
                    i++
                }
                out
            } else {
                lines
            }
            var text = section.take(TRACE_MAX_LINES).joinToString("\n") { "  $it" }.trimEnd()
            if (text.length > TRACE_MAX_CHARS) text = text.take(TRACE_MAX_CHARS) + "\n  … (truncated)"
            return text.ifBlank { null }
        }

        @RequiresApi(Build.VERSION_CODES.R)
        private fun describeExitReason(reason: Int): String = when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "exited normally"
            ApplicationExitInfo.REASON_SIGNALED -> "force-stopped or killed by signal"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "killed by the system to free memory"
            ApplicationExitInfo.REASON_CRASH -> "crashed (app exception)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed (native code)"
            ApplicationExitInfo.REASON_ANR -> "stopped: app not responding (ANR)"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to initialize"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "killed after a permission change"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for excessive resource use"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped at the user's request"
            ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by the user"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "killed because a dependency died"
            ApplicationExitInfo.REASON_OTHER -> "killed by the system (other)"
            ApplicationExitInfo.REASON_FREEZER -> "frozen by the system"
            else -> "ended for an unknown reason (code $reason)"
        }
    }
}
