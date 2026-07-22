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

/**
 * Pure formatting for the crash report's screen/activity breadcrumb (crash-
 * reporting readability work, July 22 2026). Kept out of the crash Activity so
 * the null / malformed / oversized handling is unit-testable on the JVM — the
 * crash Activity itself cannot be exercised by a plain unit test.
 *
 * The input is CustomActivityOnCrash's activity history
 * (`getActivityLogFromIntent`), the ONLY screen breadcrumb the app collects.
 * It is ACTIVITY-level (per-Activity created/resumed/paused/destroyed lines),
 * so it tells you which SCREEN was active, not which fragment/dialog/tab
 * within it — that would need navigation tracking, which is deliberately NOT
 * added here.
 *
 * Defensive by contract: never throws (the crash path is already a broken
 * state), collapses the consecutive duplicate lines the library can emit, and
 * caps both line count and total characters so a malformed or huge value can
 * neither bloat the report nor slow the log screen.
 */
object CrashReportFormat {

    const val MAX_HISTORY_LINES = 60
    const val MAX_HISTORY_CHARS = 8000

    const val NONE_RECORDED = "(no screen/activity history was recorded)"
    const val UNAVAILABLE = "(screen/activity history unavailable)"

    fun formatActivityHistory(
        raw: String?,
        maxLines: Int = MAX_HISTORY_LINES,
        maxChars: Int = MAX_HISTORY_CHARS
    ): String {
        return try {
            if (raw.isNullOrBlank()) return NONE_RECORDED
            val lines = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return NONE_RECORDED

            // Collapse only CONSECUTIVE duplicates (the library emits repeated
            // resume/pause churn for the same screen); distinct revisits are
            // kept so the real navigation trail survives.
            val collapsed = ArrayList<String>(lines.size)
            for (line in lines) {
                if (collapsed.isEmpty() || collapsed[collapsed.size - 1] != line) collapsed.add(line)
            }

            // Keep the most RECENT entries (the ones nearest the crash).
            val capped = if (collapsed.size > maxLines) collapsed.takeLast(maxLines) else collapsed
            val text = capped.joinToString("\n")
            if (text.length > maxChars) "…(truncated)\n" + text.takeLast(maxChars) else text
        } catch (_: Throwable) {
            UNAVAILABLE
        }
    }
}
