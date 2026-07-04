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

class Logger {
    companion object {
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
         * Get ads log
         * */
        fun getAdsLog(context: Context) : String {
            return EncryptedPreferences.getEncryptedPreference(context, "logs", "ads")
        }

        /**
         * @param type - type of log (crash/event/ads)
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

                // Unknown channel: drop silently rather than crash.
                else -> return
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
            try {
                collectAndLogLastExitReason(context)
            } catch (_: Throwable) { /* never let diagnostics crash startup */ }
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

        /**
         * Delete all logs
         * */
        fun deleteAllLogs(context: Context) {
            clearCrashLog(context)
            clearEventLog(context)
        }
    }
}
