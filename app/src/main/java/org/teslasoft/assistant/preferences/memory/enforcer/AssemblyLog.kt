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

package org.teslasoft.assistant.preferences.memory.enforcer

/**
 * Process-local record of the enforcer's per-turn assemblies for the debug
 * view: what was injected, why, with what score, and what was cut and why.
 * Same shape and lifetime as LoreBookInjectionLog — a debugging aid, never
 * persisted, cleared on process death.
 */
object AssemblyLog {

    /** One labelled line of the assembly ("memory", "cut", "mode", ...). */
    data class Line(
        val label: String,
        val detail: String
    )

    data class Record(
        val timestamp: Long,
        val userMessage: String,
        val companionName: String?,
        /** "compressed" | "raw" | null (no packet). */
        val packetSource: String?,
        val modes: List<String>,
        val injected: List<Line>,
        val cut: List<Line>,
        val loreNotes: List<String>,
        val scene: String?,
        /** Degradations worth seeing: keyword fallback, packet failures, ... */
        val notes: List<String>
    )

    private const val MAX_RECORDS = 50

    private val records = ArrayList<Record>()

    @Synchronized
    fun record(record: Record) {
        records.add(0, record)
        while (records.size > MAX_RECORDS) records.removeAt(records.size - 1)
    }

    @Synchronized
    fun getRecords(): List<Record> = ArrayList(records)

    @Synchronized
    fun clear() {
        records.clear()
    }
}
