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

package org.teslasoft.assistant.preferences.backup

import java.time.Instant

/**
 * Pure selection logic for the §15.6 backup walk (Build Phase 3 item 4;
 * unit-tested): which files are memory-backup candidates and in which order
 * they are tried. Newest first — corruption can be captured INTO a backup, so
 * the walk verifies each candidate and falls back to the next older one until
 * one passes; ordering is the load-bearing part.
 *
 * Candidate shapes are the two names [org.teslasoft.assistant.preferences
 * .memory.MemoryExporter] has ever written into `memory_backups/`:
 * `memory-export-<iso>.json` (the rotating automatic export) and
 * `memory-backup-<iso>.json` (the manual pre-reset backup), where `<iso>` is
 * an ISO-8601 instant with ':' replaced by '-' for filesystem safety. Both
 * are full MemorySeedCodec exports and both are equally restorable.
 */
object BackupWalkPlanner {

    private val CANDIDATE = Regex("^memory-(?:export|backup)-(.+)\\.json$")

    /** True when [name] is a memory backup candidate at all. */
    fun isCandidate(name: String): Boolean = CANDIDATE.matches(name)

    /**
     * Order [names] newest-first. The embedded stamp is an ISO instant (with
     * ':' flattened), so a plain lexical sort of the stamp is a chronological
     * sort; non-candidates are dropped. Deterministic on ties.
     */
    fun orderNewestFirst(names: List<String>): List<String> =
        names.filter { isCandidate(it) }
            .sortedWith(compareByDescending<String> { stampOf(it) }.thenByDescending { it })

    private fun stampOf(name: String): String =
        CANDIDATE.matchEntire(name)?.groupValues?.get(1) ?: ""

    /**
     * The backup's creation instant recovered from its filename, or null when
     * it cannot be parsed (the walk still uses the file, showing the file's
     * modification time instead — a date-display fallback, never a gate).
     * Reverses MemoryExporter's `nowIso().replace(":", "-")`: the date part
     * (before 'T') legitimately contains '-', so only the time part's dashes
     * are restored to ':'.
     */
    fun backupInstantMillis(name: String): Long? {
        val stamp = stampOf(name)
        if (stamp.isEmpty()) return null
        val t = stamp.indexOf('T')
        if (t < 0) return null
        val iso = stamp.substring(0, t + 1) + stamp.substring(t + 1).replace('-', ':')
        return try { Instant.parse(iso).toEpochMilli() } catch (_: Exception) { null }
    }
}
