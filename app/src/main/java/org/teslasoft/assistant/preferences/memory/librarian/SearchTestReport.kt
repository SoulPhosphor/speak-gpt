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

import org.teslasoft.assistant.preferences.memory.ImportanceRanking
import org.teslasoft.assistant.preferences.memory.enforcer.AssembledMemory
import org.teslasoft.assistant.preferences.memory.enforcer.AssemblyComponents
import org.teslasoft.assistant.preferences.memory.enforcer.ManualMemoryPriority
import org.teslasoft.assistant.preferences.memory.enforcer.PromptAssembler
import org.teslasoft.assistant.preferences.memory.enforcer.RetrievalBackfill

/**
 * What the Memory Search Test screen shows for one test message: which
 * memories a chat would send and why each other memory was left out. Pure
 * Kotlin, unit tested in SearchTestReportTest.
 *
 * It runs the same search ([LiveRetrieval.search]) and the same memory-limit
 * and memory-space walk ([RetrievalBackfill.select] with
 * [PromptAssembler.memoryCost]) that the chat's Enforcer uses, so the screen
 * shows what chats actually do. Lorebook entries, roleplay cards, and memories
 * picked before sending are not part of the test.
 */
object SearchTestReport {

    enum class Outcome {
        /** Sent within the memory limit. */
        SENT,
        /** Sent beyond the memory limit because it is a relevant +3. */
        SENT_PAST_LIMIT,
        /** Did not pass the relevance gate. */
        NOT_A_MATCH,
        /** Passed the gate, but the memory limit was already full. */
        LIMIT_FULL,
        /** Passed the gate, but did not fit in the remaining memory space. */
        NO_SPACE
    }

    data class Row(val explanation: LiveRetrieval.Explanation, val outcome: Outcome)

    data class Report(
        val sent: List<Row>,
        /** Memories that were not sent, closest first. */
        val notSent: List<Row>,
        /** The memory text the AI would receive (memories only). */
        val preview: String,
        val diagnostics: LiveRetrieval.Diagnostics
    )

    fun build(
        query: String,
        queryVector: FloatArray?,
        corpus: List<LiveRetrieval.Candidate>,
        tuning: SearchTuning,
        useImportanceRatings: Boolean,
        notSentShown: Int = 10
    ): Report {
        val scanCap = RetrievalBackfill.scanCap(tuning.maxMemories)
        val live = LiveRetrieval.search(
            query, queryVector, corpus, tuning.params, scanCap + 1,
            useImportanceRatings, includeMandatoryImportance = useImportanceRatings
        )
        val byId = live.explanations.associateBy { it.memory.memoryId }
        val pool = live.ranked.map { byId.getValue(it.memory.memoryId) }

        val noSpace = HashSet<String>()
        val assembled = HashMap<String, AssembledMemory>()
        var used = 0
        val selection = RetrievalBackfill.select(
            pool, tuning.maxMemories, scanCap,
            isMandatory = { e ->
                ImportanceRanking.isMandatory(e.memory.importance.toDouble(), useImportanceRatings)
            }
        ) { e ->
            val a = ManualMemoryPriority.assemble(e.memory, e.score.toFloat(), (e.similarity ?: 0.0).toFloat())
            val cost = PromptAssembler.memoryCost(a)
            if (used + cost > tuning.memorySpace) {
                noSpace.add(e.memory.memoryId)
                false
            } else {
                used += cost
                assembled[e.memory.memoryId] = a
                true
            }
        }

        val sentIds = selection.kept.map { it.memory.memoryId }.toSet()
        val sent = selection.kept.mapIndexed { index, e ->
            Row(e, if (index >= tuning.maxMemories) Outcome.SENT_PAST_LIMIT else Outcome.SENT)
        }
        val notSent = live.explanations
            .filter { it.memory.memoryId !in sentIds }
            .take(notSentShown)
            .map { e ->
                val outcome = when {
                    e.memory.memoryId in noSpace -> Outcome.NO_SPACE
                    e.passed -> Outcome.LIMIT_FULL
                    else -> Outcome.NOT_A_MATCH
                }
                Row(e, outcome)
            }
        val preview = PromptAssembler.render(
            AssemblyComponents(memories = selection.kept.map { assembled.getValue(it.memory.memoryId) })
        )
        return Report(sent, notSent, preview, live.diagnostics)
    }
}
