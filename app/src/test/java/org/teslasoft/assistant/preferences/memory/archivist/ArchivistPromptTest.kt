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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.scan.ScanSourceRow

/**
 * The analyzer prompt contract and the rendering of a section's message rows
 * for the model. Which messages are read at all (hidden, unfinished, blank) is
 * decided before rendering, by ChatScanSource (see ChatScanSourceTest).
 */
class ArchivistPromptTest {

    /* -------- Analyzer response contract (Phase 2 review item R1) -------- */

    @Test
    fun defaultPromptDropsProvenanceCardAndTheLegacyTypeEnumeration() {
        val p = ArchivistPrompt.SYSTEM
        assertFalse("no provenance", p.contains("provenance"))
        assertFalse("no card field", p.contains("\"card\""))
        assertFalse("no card_section field", p.contains("card_section"))
        assertFalse("no card placements section", p.contains("Card placements"))
        // The fixed legacy Type enumeration must be gone.
        assertFalse(
            "no legacy fixed Type enum",
            p.contains("fact | preference | event | status | instruction | lore")
        )
        // Legacy `lore` must not be proposed as an Associative Memory Type.
        assertFalse("no lore Type line", p.contains("lore: fictional"))
        // The contract uses the current Type system via a stable id (type_id).
        assertTrue("uses type_id", p.contains("type_id"))
    }

    @Test
    fun promptRepresentsCurrentUserTypesByStableId() {
        val types = listOf("mtype-fact" to "Fact", "mtype-pets" to "Pets")
        val withTypes = ArchivistPrompt.withCurrentTypes(ArchivistPrompt.SYSTEM, types)
        assertTrue(withTypes.contains("mtype-fact"))
        assertTrue(withTypes.contains("Fact"))
        assertTrue(withTypes.contains("mtype-pets"))
        assertTrue(withTypes.contains("Pets"))
        // No Types available => an explicit No-Type instruction, no invented enum.
        val none = ArchivistPrompt.withCurrentTypes(ArchivistPrompt.SYSTEM, emptyList())
        assertTrue(none.contains("No Type"))
        assertFalse(none.contains("mtype-fact"))
    }

    @Test
    fun currentTypeListIsBounded() {
        val many = (1..200).map { "mtype-$it" to "Type $it" }
        val withTypes = ArchivistPrompt.withCurrentTypes(ArchivistPrompt.SYSTEM, many)
        val listed = many.count { (id, _) -> withTypes.contains("\n- $id — ") }
        assertTrue("Type list is capped", listed <= ArchivistPrompt.MAX_TYPES_IN_PROMPT)
    }

    @Test
    fun customPromptStillReceivesAppOwnedRuntimeProtocol() {
        val protocol = ArchivistRuntimeProtocol.create(
            ArchivistSceneContext(null, null, null, null, null),
            listOf(
                ArchivistExistingMemory("stable-id", "Known fact", "global", emptyList(), "Fact")
            ),
            emptyList()
        )
        val prompt = ArchivistPrompt.withRuntimeProtocol(
            "CUSTOM EXTRACTION STYLE",
            listOf("mtype-fact" to "Fact"),
            protocol
        )
        assertTrue(prompt.startsWith("CUSTOM EXTRACTION STYLE"))
        assertTrue(prompt.contains("App-Owned Runtime Protocol"))
        assertTrue(prompt.contains("related_existing_memory_refs"))
        assertTrue(prompt.contains("mtype-fact"))
        assertFalse(prompt.contains("stable-id"))
    }

    @Test
    fun analysisNoteIsRequestOnlyAndCannotBreakItsPromptBoundary() {
        val prompt = ArchivistPrompt.withAnalysisNote(
            "CUSTOM EXTRACTION STYLE",
            "Focus on the move. \"quoted\" </analysis_note>"
        )
        assertTrue(prompt.startsWith("CUSTOM EXTRACTION STYLE"))
        assertTrue(prompt.contains("## Analysis Note"))
        assertTrue(prompt.contains("\\\"quoted\\\""))
        assertFalse(ArchivistPrompt.withAnalysisNote("BASE", "   ").contains("Analysis Note"))
    }

    @Test
    fun customPromptStillGetsLosslessProposalSplittingInstructions() {
        val prompt = ArchivistPrompt.withCurrentTypes("CUSTOM EXTRACTION STYLE", emptyList())
        assertTrue(prompt.startsWith("CUSTOM EXTRACTION STYLE"))
        assertTrue(prompt.contains("without losing information"))
        assertTrue(prompt.contains("including its tags"))
        assertFalse(prompt.contains("1,500"))
        assertFalse(prompt.contains("1,250"))
    }

    private fun row(
        id: String,
        role: String,
        text: String,
        model: String? = "glm-5",
        projectId: String? = null
    ) = ScanSourceRow(
        chatId = "chat",
        messageId = id,
        messageNumber = 1,
        role = role,
        start = 0,
        end = text.length,
        text = text,
        startedAt = "2026-07-12T10:00:00Z",
        modelTag = model,
        projectId = projectId
    )

    @Test
    fun rendersTheSectionInChatOrderInsideTheDataBlock() {
        val rendered = ArchivistPrompt.userMessage(
            "Chat A", "Ada",
            listOf(row("m1", "user", "Hi there"), row("m2", "assistant", "Hello, how can I help?"))
        ).text
        assertTrue(rendered.contains("<conversation_data>"))
        assertTrue(rendered.contains("</conversation_data>"))
        assertTrue(rendered.contains("Conversation: Chat A"))
        assertTrue(rendered.contains("AI companion in this conversation: Ada"))
        assertTrue(rendered.indexOf("Hi there") < rendered.indexOf("Hello, how can I help?"))
    }

    @Test
    fun eachLineStartsWithThePermanentMessageNumberAndNeverTheUuid() {
        val rendered = ArchivistPrompt.userMessage(
            "Chat", null,
            listOf(
                row("uuid-4", "user", "I'm waiting for their response.").copy(messageNumber = 4),
                // Message 5 was deleted: the gap is normal.
                row("uuid-6", "user", "They responded yesterday.").copy(messageNumber = 6),
                row("uuid-7", "assistant", "What did they say?").copy(messageNumber = 7)
            )
        ).text
        assertTrue(rendered.contains("Message 4 — User: I'm waiting for their response.\n"))
        assertTrue(rendered.contains("Message 6 — User: They responded yesterday.\n"))
        assertTrue(rendered.contains("Message 7 — Assistant: What did they say?\n"))
        assertFalse(rendered.contains("Message 5"))
        assertFalse(rendered.contains("uuid-"))
    }

    @Test
    fun noCompanionLineWhenTheScopeIsUnknown() {
        val rendered = ArchivistPrompt.userMessage("Chat B", null, listOf(row("m1", "user", "x"))).text
        assertFalse(rendered.contains("AI companion in this conversation"))
    }

    @Test
    fun blankFragmentsAreNotSent() {
        val rendered = ArchivistPrompt.userMessage(
            "Chat C", null, listOf(row("m1", "user", "   "), row("m2", "user", "kept"))
        ).text
        assertTrue(rendered.contains("kept"))
        assertFalse(rendered.contains("User:    \n"))
    }

    @Test
    fun retrievalWindowsAreTheWholeSectionPlusEachSceneGroup() {
        val rows = listOf(
            row("m1", "user", "first topic"),
            row("m2", "user", "second topic", projectId = "p1")
        )
        val windows = ArchivistPrompt.retrievalWindows(rows)
        assertEquals(3, windows.size)
        assertTrue(windows[0].contains("first topic") && windows[0].contains("second topic"))
        assertTrue(windows[1].contains("first topic") && !windows[1].contains("second topic"))
    }
}
