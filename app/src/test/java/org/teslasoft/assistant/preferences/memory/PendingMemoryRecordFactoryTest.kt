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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one canonical Pending filing shape (canonical recovery plan Phase 2, item
 * 8, plus review items R2/R3):
 *
 *  - API Memory Assistant, computer import, and manual creation produce the SAME
 *    canonical Pending record for equivalent approved data.
 *  - The candidate carries no source authorship (origin), no importance, and no
 *    transport/chat identity; none of it reaches the memory.
 *  - Every newly filed proposal begins at importance 0, and the legacy origin
 *    column gets one fixed inert placeholder for every route.
 *
 * The three transport wrappers below carry DIFFERENT transport-only data and
 * converge — with no shared semantic origin hard-coded — on candidates that have
 * no origin field at all.
 */
class PendingMemoryRecordFactoryTest {

    // A transport wrapper: the approved memory fields PLUS transport-only data
    // that must never reach the memory (and differs per route).
    private data class TransportWrapper(
        val content: String,
        val scope: String,
        val typeId: String?,
        val tags: List<String>,
        val transport: String,            // "api" / "computer" / "manual"
        val conversationPolicy: String?,
        val analysisNote: String?,
        val chatId: String?,
        val processingMethod: String?
    )

    /** The convergence point: a wrapper validates to a candidate, dropping every
     *  transport-only field. The candidate has no origin and no importance to
     *  supply. */
    private fun TransportWrapper.toCandidate(): MemoryCandidate {
        val result = MemoryCandidateValidator.validateGeneral(
            scope = scope,
            content = content,
            typeId = typeId,
            tags = tags
        )
        return (result as CandidateResult.Valid).candidate
    }

    @Test
    fun threeRoutesWithDifferentTransportDataProduceIdenticalRecords() {
        val approvedContent = "the user's dog is named Pixel"
        // Same approved data, deliberately DIFFERENT transport-only fields.
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

        // The candidates carry no origin field at all — nothing distinguishes the
        // route once transport-only data is dropped.
        assertEquals(api.toCandidate(), computer.toCandidate())
        assertEquals(api.toCandidate(), manual.toCandidate())

        val id = "m-fixed"
        val now = "2026-08-05T00:00:00Z"
        val rApi = PendingMemoryRecordFactory.build(api.toCandidate(), id, now)
        val rComputer = PendingMemoryRecordFactory.build(computer.toCandidate(), id, now)
        val rManual = PendingMemoryRecordFactory.build(manual.toCandidate(), id, now)

        assertEquals("api and computer must file identically", rApi, rComputer)
        assertEquals("api and manual must file identically", rApi, rManual)

        // The legacy origin column is the SAME fixed inert placeholder for every
        // route — never a per-route/transport value.
        assertEquals(PendingMemoryRecordFactory.COMPAT_ORIGIN, rApi.origin)
        assertEquals(rApi.origin, rComputer.origin)
        assertEquals(rApi.origin, rManual.origin)
    }

    @Test
    fun canonicalRecordCarriesNoTransportOriginImportanceOrChatIdentity() {
        val wrapper = TransportWrapper(
            "a fact", "global", null, emptyList(),
            transport = "api", conversationPolicy = "policy", analysisNote = "note",
            chatId = "chat-1", processingMethod = "api-run"
        )
        val record = PendingMemoryRecordFactory.build(wrapper.toCandidate(), "m-1", "2026-08-05T00:00:00Z")

        // No permanent provenance (review finding 1) and no chat identity.
        assertNull(record.provenanceSource)
        assertNull(record.provenanceConfidence)
        assertNull(record.provenanceNotedOn)
        assertNull(record.provenanceContext)
        assertNull(record.sourceChatId)
        // The candidate has no source authorship; the legacy origin column is the
        // one fixed inert placeholder, never the api/computer transport (item R2).
        assertEquals(PendingMemoryRecordFactory.COMPAT_ORIGIN, record.origin)
        assertFalse(record.origin == "api" || record.origin == "computer")
        // Structural importance 0 (item R3).
        assertEquals(0, record.importance)
        // Canonical Pending contract: a draft with no protection/handling fields.
        assertEquals("draft", record.status)
        assertNull(record.protectionJson)
        // Legacy kind is inert (item B) and no card-placement metadata (item C).
        assertEquals("", record.kind)
        assertNull(record.suggestedCardType)
        assertNull(record.suggestedCardId)
        assertNull(record.suggestedSection)
        // No MemoryRecord field holds conversation policy / analysis note /
        // processing method — verify none leaked into a text field.
        assertFalse(record.content.contains("policy"))
        assertFalse(record.content.contains("note"))
    }

    @Test
    fun theCandidateContractHasNoImportanceOrOriginInputAtAll() {
        // Item R2/R3 structurally: the candidate objects expose no importance and
        // no origin field, so a nonzero analyzer/import importance or a route
        // authorship cannot enter the contract in the first place.
        val general = (MemoryCandidateValidator.validateGeneral("global", "a fact")
            as CandidateResult.Valid).candidate
        val comp = (MemoryCandidateValidator.validateCompanion(
            content = "prefers formal greetings",
            companionTargetIds = listOf("c-a"),
            intendedCompanionId = "c-a",
            availableCompanionIds = setOf("c-a")
        ) as CandidateResult.Valid).candidate
        for (c in listOf<MemoryCandidate>(general, comp)) {
            val fields = c.javaClass.declaredFields.map { it.name }
            assertFalse("candidate must have no importance input", fields.any { it.contains("importance", true) })
            assertFalse("candidate must have no origin input", fields.any { it.equals("origin", true) })
        }
        // And every built record still begins at importance 0.
        assertEquals(0, PendingMemoryRecordFactory.build(general, "m-g", "2026-08-05T00:00:00Z").importance)
        assertEquals(0, PendingMemoryRecordFactory.build(comp, "m-c", "2026-08-05T00:00:00Z").importance)
    }

    @Test
    fun companionCandidateFilesAsCanonicalCompanionDraft() {
        val comp = (MemoryCandidateValidator.validateCompanion(
            content = "prefers to be greeted formally",
            companionTargetIds = listOf("c-a"),
            intendedCompanionId = "c-a",
            availableCompanionIds = setOf("c-a")
        ) as CandidateResult.Valid).candidate
        val record = PendingMemoryRecordFactory.build(comp, "m-2", "2026-08-05T00:00:00Z")

        assertEquals(SCOPE_COMPANION, record.scope)
        assertEquals(listOf("c-a"), record.companionIds)
        assertEquals("draft", record.status)
    }
}
