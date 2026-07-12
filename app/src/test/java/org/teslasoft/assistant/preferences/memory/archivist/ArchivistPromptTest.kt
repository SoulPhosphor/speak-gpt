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
import org.teslasoft.assistant.preferences.memory.TranscriptRecord

/**
 * Round 5, change 3: ArchivistPrompt.userMessage must (a) exclude assistant
 * turns Round 3 marked `complete:false` from what the model sees, (b) keep the
 * user turn beside an excluded fragment, and (c) report an accurate count of
 * the excluded assistant fragments (the in-memory RunOutcome diagnostic). It
 * must not change the legacy "absent = complete" or malformed-JSON behavior.
 */
class ArchivistPromptTest {

    private fun transcript(content: String, startedAt: String? = "2026-07-12T10:00:00Z"): TranscriptRecord =
        TranscriptRecord(
            transcriptId = "t-1",
            chatId = "chat-1",
            companionId = null,
            worldId = null,
            roleplayCharacterId = null,
            userPersonaId = null,
            source = "live",
            startedAt = startedAt,
            endedAt = startedAt,
            content = content,
            modelTag = "glm-5",
            quickSettingsJson = null,
            reviewStatus = "pending",
            processedAt = null
        )

    @Test
    fun completeConversationDropsNothing() {
        val json = """
            [{"role":"user","content":"Hi there"},
             {"role":"assistant","content":"Hello, how can I help?"}]
        """.trimIndent()
        val rendered = ArchivistPrompt.userMessage("Chat A", "Ada", listOf(transcript(json)))

        assertEquals(0, rendered.incompleteAssistantTurnsDropped)
        assertTrue(rendered.text.contains("User: Hi there"))
        assertTrue(rendered.text.contains("Assistant: Hello, how can I help?"))
    }

    @Test
    fun incompleteAssistantTurnIsExcludedButUserTurnKept() {
        val json = """
            [{"role":"user","content":"What is the capital of France?"},
             {"role":"assistant","content":"The capital of Fra","complete":false}]
        """.trimIndent()
        val rendered = ArchivistPrompt.userMessage("Chat B", null, listOf(transcript(json)))

        assertEquals(1, rendered.incompleteAssistantTurnsDropped)
        // The user's own turn beside the fragment is preserved...
        assertTrue(rendered.text.contains("User: What is the capital of France?"))
        // ...but the truncated assistant fragment never reaches the model.
        assertFalse(rendered.text.contains("The capital of Fra"))
        assertFalse(rendered.text.contains("Assistant:"))
    }

    @Test
    fun countSumsAcrossTranscriptsAndTurns() {
        val a = """
            [{"role":"user","content":"one"},
             {"role":"assistant","content":"frag1","complete":false},
             {"role":"assistant","content":"good reply"}]
        """.trimIndent()
        val b = """
            [{"role":"user","content":"two"},
             {"role":"assistant","content":"frag2","complete":false}]
        """.trimIndent()
        val rendered = ArchivistPrompt.userMessage(
            "Chat C", "Ada", listOf(transcript(a), transcript(b))
        )

        assertEquals(2, rendered.incompleteAssistantTurnsDropped)
        assertTrue(rendered.text.contains("Assistant: good reply"))
        assertFalse(rendered.text.contains("frag1"))
        assertFalse(rendered.text.contains("frag2"))
        assertTrue(rendered.text.contains("User: one"))
        assertTrue(rendered.text.contains("User: two"))
    }

    @Test
    fun legacyTurnWithoutCompleteKeyIsTreatedAsComplete() {
        // Absent "complete" (every legacy row) means complete — not dropped.
        val json = """
            [{"role":"assistant","content":"a finished legacy reply"}]
        """.trimIndent()
        val rendered = ArchivistPrompt.userMessage("Chat D", null, listOf(transcript(json)))

        assertEquals(0, rendered.incompleteAssistantTurnsDropped)
        assertTrue(rendered.text.contains("Assistant: a finished legacy reply"))
    }

    @Test
    fun explicitCompleteTrueIsKept() {
        val json = """
            [{"role":"assistant","content":"explicitly done","complete":true}]
        """.trimIndent()
        val rendered = ArchivistPrompt.userMessage("Chat E", null, listOf(transcript(json)))

        assertEquals(0, rendered.incompleteAssistantTurnsDropped)
        assertTrue(rendered.text.contains("Assistant: explicitly done"))
    }

    @Test
    fun malformedContentFallsBackToRawTextAndCountsNoDrops() {
        // Unchanged fallback behavior: unparseable capture is passed through
        // rather than losing the conversation, and reports zero drops.
        val rendered = ArchivistPrompt.userMessage(
            "Chat F", null, listOf(transcript("this is not json"))
        )

        assertEquals(0, rendered.incompleteAssistantTurnsDropped)
        assertTrue(rendered.text.contains("this is not json"))
    }
}
