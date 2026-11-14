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

package org.teslasoft.assistant.preferences.memory.librarian

/** Rendered diagnostic fragments shared by production logging and tests. */
object RecallDiagnostics {
    fun queryFailure(background: Boolean, cause: String): String =
        if (background) "Memory Assistant could not process its background search with the on-device model, so it searched by keywords only. Cause: ${cause.trimEnd('.')}."
        else "Memory search could not read this message with the on-device model, so it searched by keywords only. Cause: ${cause.trimEnd('.')}."

    fun sectionFailure(reading: LongMessageSearch.Reading): String? {
        if (reading.failures.isEmpty()) return null
        if (reading.failures.size == 1) return "The embedding model couldn't process one additional section. That section was skipped, but the full-message search still ran. Error: ${reading.failures.single().cause} (section ${reading.failures.single().section.number})"
        val details = reading.failures.groupBy { it.cause }.entries.joinToString("; ") { (cause, failures) ->
            "sections ${failures.joinToString(", ") { it.section.number.toString() }}: $cause"
        }
        return "The embedding model couldn't process ${reading.failures.size} additional sections. Those sections were skipped, but the full-message search still ran." +
            (if (reading.repeatedFailure) " Further sections were not attempted after repeated identical failures." else "") +
            " Errors: $details"
    }

    fun mode(hasModel: Boolean, semantic: Boolean, withVector: Int, eligible: Int): String = when {
        !hasModel -> "Keyword search only (embedding model unavailable)"
        !semantic && withVector > 0 -> "Keyword search only (message embedding failed)"
        !semantic -> "Keyword search only (no eligible embeddings ready)"
        withVector < eligible -> "meaning + keyword, $withVector of $eligible have vectors"
        else -> "meaning + keyword"
    }

    fun summary(eligible: Int, candidates: Int, mode: String, injected: Int, budget: Int, overlap: Int): String =
        "saved memories: eligible $eligible, candidates $candidates ($mode), injected $injected, omitted budget $budget / lore-overlap $overlap"
}
