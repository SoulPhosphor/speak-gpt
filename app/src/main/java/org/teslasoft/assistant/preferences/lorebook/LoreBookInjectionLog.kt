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

package org.teslasoft.assistant.preferences.lorebook

/**
 * In-memory record of recent lorebook injections, used by the debug view to show
 * "which memory was injected and why" (a Phase 1 requirement).
 *
 * This is intentionally process-local and not persisted: it's a debugging aid,
 * not user data. It is cleared when the app process dies.
 */
object LoreBookInjectionLog {

    data class Record(
        val timestamp: Long,
        val userMessage: String,
        val matches: List<LoreBookMatch>,
        /** How many active books were searched this turn; -1 = the lorebook
         *  store was unavailable (see the Event log for why). Zero-match turns
         *  are recorded too — "searched 3 books, matched nothing" and "had no
         *  books to search" are different diagnoses and the debug view must be
         *  able to tell them apart. */
        val activeBooks: Int = -1
    )

    private const val MAX_RECORDS = 50

    private val records = ArrayList<Record>()

    @Synchronized
    fun record(userMessage: String, matches: List<LoreBookMatch>, activeBooks: Int = -1) {
        records.add(0, Record(System.currentTimeMillis(), userMessage, matches, activeBooks))
        while (records.size > MAX_RECORDS) {
            records.removeAt(records.size - 1)
        }
    }

    @Synchronized
    fun getRecords(): List<Record> = ArrayList(records)

    @Synchronized
    fun clear() {
        records.clear()
    }
}
