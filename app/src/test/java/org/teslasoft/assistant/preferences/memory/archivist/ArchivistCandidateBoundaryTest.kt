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

package org.teslasoft.assistant.preferences.memory.archivist

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.FrozenChatRangeExecutor

class ArchivistCandidateBoundaryTest {

    private fun draft(
        content: String,
        related: List<String> = emptyList(),
        tags: List<String> = emptyList()
    ) = ArchivistResponseParser.DraftMemory(
        content = content,
        scope = "real_life",
        typeIdSuggestion = "type-fact",
        tags = tags,
        targetName = null,
        relatedExistingMemoryIds = related,
        scene = ArchivistSceneContext(null, null, null, null, null)
    )

    @Test
    fun laterChunkReceivesOnlyEarlierValidatedOutputs() = runBlocking {
        val seen = ArrayList<List<String>>()
        val committed = FrozenChatRangeExecutor.executeWithStaged(
            chunks = listOf("one", "two", "three"),
            analyzeChunk = { chunk, earlier ->
                seen.add(earlier)
                "candidate-$chunk"
            },
            commit = { it }
        )

        assertEquals(
            listOf(
                emptyList(),
                listOf("candidate-one"),
                listOf("candidate-one", "candidate-two")
            ),
            seen
        )
        assertEquals(
            listOf("candidate-one", "candidate-two", "candidate-three"),
            committed
        )
    }

    @Test
    fun laterChunkFailureDoesNotExposeEarlierCandidateSet() = runBlocking {
        var commitCalled = false

        try {
            FrozenChatRangeExecutor.executeWithStaged(
                chunks = listOf(1, 2, 3),
                analyzeChunk = { chunk, _ ->
                    if (chunk == 3) error("late failure")
                    "candidate-$chunk"
                },
                commit = {
                    commitCalled = true
                    it
                }
            )
        } catch (_: IllegalStateException) {
            // Expected deterministic fake failure; no external model is used.
        }

        assertFalse(commitCalled)
    }

    @Test
    fun exactCrossChunkDuplicateFilesOnceAndKeepsRelationshipHint() {
        val collected = ArchivistCandidateBoundary.collect(
            listOf(
                listOf(draft("The user's favorite color is purple.")),
                listOf(
                    draft(
                        "  THE USER'S FAVORITE COLOR IS PURPLE.  ",
                        related = listOf("memory-green"),
                        tags = listOf("preference")
                    )
                )
            )
        )

        assertEquals(1, collected.memories.size)
        assertEquals(1, collected.exactDuplicatesRemoved)
        assertEquals(listOf("memory-green"), collected.memories.single().relatedExistingMemoryIds)
        assertEquals(listOf("preference"), collected.memories.single().tags)
    }

    @Test
    fun locallyEstablishedCorrectionKeepsBothLinkedForPossibleMatchReview() {
        val collected = ArchivistCandidateBoundary.collect(
            listOf(
                listOf(draft("The user's favorite color is green.", listOf("memory-old"))),
                listOf(draft("The user's favorite color is purple now.", listOf("memory-old")))
            )
        )

        assertEquals(2, collected.memories.size)
        assertTrue(collected.memories.all { it.relatedExistingMemoryIds == listOf("memory-old") })
        assertEquals(0, collected.exactDuplicatesRemoved)
    }

    @Test
    fun runtimeProtocolCarriesBoundedEarlierCandidatesWithoutStableIds() {
        val earlier = (1..14).map {
            ArchivistEarlierCandidate(
                content = "Earlier candidate $it",
                scope = "project",
                targetNames = listOf("Memory Repair"),
                typeName = "Fact",
                tags = emptyList()
            )
        }
        val protocol = ArchivistRuntimeProtocol.create(
            scene = ArchivistSceneContext(null, null, null, null, "stable-project-id"),
            existingMemories = emptyList(),
            validTargets = listOf(
                ArchivistTarget("stable-project-id", "project", "Memory Repair", "active")
            ),
            earlierCandidates = earlier
        )
        val rendered = ArchivistRuntimeProtocol.render(protocol)

        assertEquals(
            ArchivistRuntimeProtocol.MAX_EARLIER_CANDIDATES_IN_PROMPT,
            protocol.earlierCandidates.size
        )
        assertTrue(rendered.contains("already_proposed_this_run_data"))
        assertTrue(rendered.contains("Earlier candidate 14"))
        assertFalse(rendered.contains("Earlier candidate 1\""))
        assertFalse(rendered.contains("stable-project-id"))
    }

    @Test
    fun runtimeProtocolCarriesEarlierModelRuleAsProposalData() {
        val protocol = ArchivistRuntimeProtocol.create(
            scene = ArchivistSceneContext(null, null, null, null, null),
            existingMemories = emptyList(),
            validTargets = emptyList(),
            earlierCandidates = listOf(
                ArchivistEarlierCandidate(
                    content = "Answer in complete sentences.",
                    scope = null,
                    targetNames = emptyList(),
                    typeName = null,
                    tags = emptyList(),
                    stream = "model_rule"
                )
            )
        )

        val rendered = ArchivistRuntimeProtocol.render(protocol)
        assertTrue(rendered.contains("\"stream\":\"model_rule\""))
        assertTrue(rendered.contains("Answer in complete sentences."))
    }
}
