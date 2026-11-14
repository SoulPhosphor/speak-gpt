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
 * picked before sending are not part of the test. A long message is also
 * searched in sections ([LongMessageSearch]), as chats do.
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
        /** Candidate scanning ended with slots still open. */
        SCAN_LIMIT,
        /** Passed the gate, but did not fit in the remaining memory space. */
        NO_SPACE
    }

    data class Row(
        val explanation: LiveRetrieval.Explanation,
        val outcome: Outcome,
        /** Set when only a section of the message found this memory; the
         *  explanation's numbers are then the memory against that section. */
        val section: LongMessageSearch.Match? = null,
        val sectionEvaluation: LongMessageSearch.Evaluation? = null
    )

    data class Report(
        val sent: List<Row>,
        /** Memories that were not sent, closest first. */
        val notSent: List<Row>,
        /** The memory text the AI would receive (memories only). */
        val preview: String,
        val diagnostics: LiveRetrieval.Diagnostics,
        /** What the section search of a long message did. */
        val sections: LongMessageSearch.Reading = LongMessageSearch.Reading.NONE
    )

    fun build(
        query: String,
        queryVector: FloatArray?,
        corpus: List<LiveRetrieval.Candidate>,
        tuning: SearchTuning,
        useImportanceRatings: Boolean,
        notSentShown: Int = 10,
        sections: LongMessageSearch.Reading = LongMessageSearch.Reading.NONE
    ): Report {
        val scanCap = RetrievalBackfill.scanCap(tuning.maxMemories)
        val index = LiveRetrieval.index(corpus)
        val result = SavedMemorySearch.search(query, queryVector, index, tuning.params, scanCap + 1,
            useImportanceRatings) { sections }
        val live = result.whole
        val byId = live.explanations.associateBy { it.memory.memoryId }
        // Keep all qualifying section matches for truthful exclusion reasons,
        // even when the live candidate pool's scan cap prevents selection.
        val bySection = result.sectionMatches.associateBy { it.explanation.memory.memoryId }
        val evaluations = HashMap<String, LongMessageSearch.Evaluation>()
        val closestFirst = compareBy<LongMessageSearch.Evaluation> { it.rejectedBy.size }
            .thenByDescending { it.explanation.matchedTerms.isNotEmpty() }
            .thenByDescending { it.explanation.similarity ?: -1.0 }
            .thenByDescending { it.explanation.score }
        for (evaluation in LongMessageSearch.evaluate(result.sections, index, tuning.params, useImportanceRatings)) {
            if (evaluation.explanation.similarity == null) continue
            val id = evaluation.explanation.memory.memoryId
            val previous = evaluations[id]
            if (previous == null || closestFirst.compare(evaluation, previous) < 0) evaluations[id] = evaluation
        }

        val pool = result.ranked.map { bySection[it.memory.memoryId]?.explanation ?: byId.getValue(it.memory.memoryId) }

        val noSpace = HashSet<String>()
        val assembled = HashMap<String, AssembledMemory>()
        var used = 0
        val selection = RetrievalBackfill.select(
            pool, tuning.maxMemories, scanCap,
            isMandatory = { e ->
                e.memory.memoryId !in bySection &&
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
            Row(
                e,
                if (index >= tuning.maxMemories) Outcome.SENT_PAST_LIMIT else Outcome.SENT,
                bySection[e.memory.memoryId],
                bySection[e.memory.memoryId]?.let { LongMessageSearch.Evaluation(it, result.sections.settings.sectionSimilarityMin) }
            )
        }
        val unsent = live.explanations
            .filter { it.memory.memoryId !in sentIds }
            .map { whole ->
                val match = bySection[whole.memory.memoryId]
                val evaluation = match?.let { LongMessageSearch.Evaluation(it, result.sections.settings.sectionSimilarityMin) }
                    ?: if (whole.passed) null else evaluations[whole.memory.memoryId]
                val e = match?.explanation ?: evaluation?.explanation ?: whole
                val outcome = when {
                    e.memory.memoryId in noSpace -> Outcome.NO_SPACE
                    whole.passed || match != null -> if (selection.kept.size < tuning.maxMemories)
                        Outcome.SCAN_LIMIT else Outcome.LIMIT_FULL
                    else -> Outcome.NOT_A_MATCH
                }
                Row(e, outcome, match, evaluation)
            }.sortedWith(compareBy<Row> {
                when {
                    it.outcome == Outcome.NOT_A_MATCH -> 2
                    it.section != null -> 1
                    else -> 0
                }
            }.thenBy { if (it.outcome == Outcome.NOT_A_MATCH)
                it.sectionEvaluation?.rejectedBy?.size ?: LongMessageSearch.Rejection.entries.size else 0 }
                .thenByDescending { it.explanation.score })
        // Qualified exclusions come first, followed by candidates closest to
        // the complete section rule. Whole-message scores alone can hide the
        // useful near-misses below ten unrelated, high-similarity rows.
        // A match that was not sent is always shown, even when its
        // whole-message score would place it below the rows shown.
        val notSent = unsent.take(notSentShown) +
            unsent.drop(notSentShown).filter {
                it.section != null && it.explanation.memory.memoryId in result.sectionIds
            }
        val preview = PromptAssembler.render(
            AssemblyComponents(memories = selection.kept.map { assembled.getValue(it.memory.memoryId) })
        )
        return Report(sent, notSent, preview, live.diagnostics, result.sections)
    }
}
