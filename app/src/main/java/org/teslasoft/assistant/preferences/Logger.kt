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

import android.content.Context
import java.time.LocalDateTime
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
            if (level == "info" || level == "error" || level == "warning" || level == "debug" || level == "verbose") {
                // Local time, second precision. The ISO/UTC instant this used
                // to print ("2026-06-12T19:03:31.903759Z") was unreadable on a
                // phone and in the wrong timezone, which made correlating a
                // log line with "the turn that just failed" impossible.
                val timestamp = LocalDateTime.now().format(LOG_TIME_FORMAT)
                val logString =
                    "[$timestamp] [$tag] [${level.uppercase()}] $message\n"
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

                    else -> {
                        error("Invalid log type")
                    }
                }
            } else {
                error("Invalid log level")
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
         * Delete all logs
         * */
        fun deleteAllLogs(context: Context) {
            clearCrashLog(context)
            clearEventLog(context)
        }
    }
}
