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
 *
 * [matched] is everything found this turn — what a trigger fired on, before
 * cross-book dedup or the injection budget. [injected] is what actually
 * reached the prompt. [cut] is [matched] minus [injected], each paired with
 * the specific reason it did not survive (counterplan Step 1.6 — previously
 * only the raw matches were recorded, so a truncated or deduped turn looked
 * identical to a fully-injected one in the debug view).
 */
object LoreBookInjectionLog {

    /** One matched entry that did not make it into the prompt, and why. */
    data class Cut(val match: LoreBookMatch, val reason: String)

    data class Record(
        val timestamp: Long,
        val userMessage: String,
        val matched: List<LoreBookMatch>,
        /** How many active books were searched this turn; -1 = the lorebook
         *  store was unavailable (see the Event log for why). Zero-match turns
         *  are recorded too — "searched 3 books, matched nothing" and "had no
         *  books to search" are different diagnoses and the debug view must be
         *  able to tell them apart. */
        val activeBooks: Int = -1,
        /** What actually reached the prompt. Defaults to [matched] for a
         *  caller that hasn't computed dedup/budget yet, though every current
         *  caller passes its real post-dedup, post-budget set explicitly. */
        val injected: List<LoreBookMatch> = matched,
        val cut: List<Cut> = emptyList()
    )

    private const val MAX_RECORDS = 50

    private val records = ArrayList<Record>()

    @Synchronized
    fun record(
        userMessage: String,
        matched: List<LoreBookMatch>,
        activeBooks: Int = -1,
        injected: List<LoreBookMatch> = matched,
        cut: List<Cut> = emptyList()
    ) {
        records.add(0, Record(System.currentTimeMillis(), userMessage, matched, activeBooks, injected, cut))
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
