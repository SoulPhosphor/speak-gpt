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

package org.teslasoft.assistant.preferences.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one canonical Pending filing shape (canonical recovery plan Phase 2, item
 * 8). Covers:
 *
 *  12. API Memory Assistant, computer import, and manual creation produce the
 *      SAME canonical Pending Associative Memory object for equivalent approved
 *      data.
 *  13. The API/computer transport origin does not appear in the stored memory
 *      data (nor, therefore, on the Pending card, which renders that data).
 *
 * These are pure builder tests: three transport wrappers, each carrying its own
 * junk (conversation policy, analysis note, chat identity, processing method),
 * converge on the same validated [MemoryCandidate] and then the same
 * [PendingMemoryRecordFactory] output.
 */
class PendingMemoryRecordFactoryTest {

    // A transport wrapper: approved memory fields PLUS transport-only junk that
    // must never reach the memory. All three origins share this shape here only
    // to make the test explicit; in production each has its own wrapper.
    private data class TransportWrapper(
        val content: String,
        val scope: String,
        val typeId: String?,
        val tags: List<String>,
        // Junk the item 8 rules forbid on the memory:
        val transport: String,            // "api" / "computer" / "manual"
        val conversationPolicy: String?,
        val analysisNote: String?,
        val chatId: String?,
        val processingMethod: String?
    )

    /** The convergence point: a wrapper validates to a candidate, dropping every
     *  transport-only field. */
    private fun TransportWrapper.toCandidate(): MemoryCandidate {
        val result = MemoryCandidateValidator.validateGeneral(
            scope = scope,
            content = content,
            typeId = typeId,
            tags = tags,
            importance = 0,
            origin = "archivist"
        )
        return (result as CandidateResult.Valid).candidate
    }

    @Test
    fun threeOriginsProduceTheSameCanonicalObject() {
        val approvedContent = "the user's dog is named Pixel"
        val api = TransportWrapper(
            approvedContent, "global", "mtype-fact", listOf("pets"),
            transport = "api", conversationPolicy = "standard", analysisNote = "found in chat 12",
            chatId = "chat-12", processingMethod = "api-run"
        )
        val computer = TransportWrapper(
            approvedContent, "global", "mtype-fact", listOf("pets"),
            transport = "computer", conversationPolicy = "review-package", analysisNote = "package note",
            chatId = "chat-99", processingMethod = "computer-import"
        )
        val manual = TransportWrapper(
            approvedContent, "global", "mtype-fact", listOf("pets"),
            transport = "manual", conversationPolicy = null, analysisNote = null,
            chatId = null, processingMethod = null
        )

        // Same injected id + timestamp isolates the shape from id/clock noise.
        val id = "m-fixed"
        val now = "2026-08-05T00:00:00Z"
        val rApi = PendingMemoryRecordFactory.build(api.toCandidate(), id, now)
        val rComputer = PendingMemoryRecordFactory.build(computer.toCandidate(), id, now)
        val rManual = PendingMemoryRecordFactory.build(manual.toCandidate(), id, now)

        assertEquals("API and computer must file identically", rApi, rComputer)
        assertEquals("API and manual must file identically", rApi, rManual)
    }

    @Test
    fun canonicalRecordCarriesNoTransportOrChatIdentity() {
        val wrapper = TransportWrapper(
            "a fact", "global", null, emptyList(),
            transport = "api", conversationPolicy = "policy", analysisNote = "note",
            chatId = "chat-1", processingMethod = "api-run"
        )
        val record = PendingMemoryRecordFactory.build(wrapper.toCandidate(), "m-1", "2026-08-05T00:00:00Z")

        // No permanent provenance is stored on a canonical Pending memory
        // (review finding 1): source, confidence, noted-on, chat name, chat id.
        assertNull(record.provenanceSource)
        assertNull(record.provenanceConfidence)
        assertNull(record.provenanceNotedOn)
        assertNull(record.provenanceContext)
        assertNull(record.sourceChatId)
        // The transport origin is not stored. `origin` is authorship only, one
        // of the record-source values — never the api/computer transport.
        org.junit.Assert.assertTrue(record.origin in setOf("user", "seed", "archivist"))
        org.junit.Assert.assertFalse(record.origin == "api" || record.origin == "computer")
        // Canonical Pending contract: a draft with no protection/handling fields.
        assertEquals("draft", record.status)
        assertNull(record.protectionJson)
        // No MemoryRecord field exists to hold conversation policy / analysis
        // note / processing method — verify none leaked into a text field.
        org.junit.Assert.assertFalse(record.content.contains("policy"))
        org.junit.Assert.assertFalse(record.content.contains("note"))
    }

    @Test
    fun companionCandidateFilesAsCanonicalCompanionDraft() {
        val comp = (MemoryCandidateValidator.validateCompanion(
            content = "prefers to be greeted formally",
            companionTargetIds = listOf("c-a"),
            intendedCompanionId = "c-a",
            availableCompanionIds = setOf("c-a"),
            origin = "archivist"
        ) as CandidateResult.Valid).candidate
        val record = PendingMemoryRecordFactory.build(comp, "m-2", "2026-08-05T00:00:00Z")

        assertEquals(SCOPE_COMPANION, record.scope)
        assertEquals(listOf("c-a"), record.companionIds)
        assertEquals("draft", record.status)
    }
}
