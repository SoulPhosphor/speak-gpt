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

package org.teslasoft.assistant.util.summarizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummarizerPromptsTest {

    @Test
    fun lengthPlaceholderIsReplacedEverywhere() {
        val rendered = SummarizerPrompts.render(SummarizerPrompts.STORYTELLER, 250)
        assertFalse(rendered.contains("{length}"))
        assertTrue(rendered.contains("under 250 words"))
    }

    @Test
    fun onlySlotsOneAndTwoShipWithPrompts() {
        assertEquals(SummarizerPrompts.STORYTELLER, SummarizerPrompts.shippedPrompt(0))
        assertEquals(SummarizerPrompts.REPORTER, SummarizerPrompts.shippedPrompt(1))
        assertEquals("", SummarizerPrompts.shippedPrompt(2))
        assertEquals("", SummarizerPrompts.shippedPrompt(3))
        assertEquals("", SummarizerPrompts.shippedPrompt(4))
    }

    @Test
    fun foldInBodyCarriesPromptSummaryAndDepartingMessages() {
        val body = SummarizerPrompts.foldInRequestBody(
            renderedPrompt = "PROMPT",
            existingSummary = "The story so far.",
            departingMessages = listOf(
                Pair("User", "Hello there"),
                Pair("Assistant", "Hi!")
            )
        )
        assertTrue(body.startsWith("PROMPT"))
        assertTrue(body.contains("The story so far."))
        assertTrue(body.contains("User: Hello there"))
        assertTrue(body.contains("Assistant: Hi!"))
    }

    @Test
    fun theAttachmentRuleRidesAlongOnlyWhenAMarkerIsFolding() {
        val marker = "<attachment-reference>{\"id\":\"a\"}</attachment-reference>"
        val withMarker = SummarizerPrompts.foldInRequestBody(
            renderedPrompt = "PROMPT",
            existingSummary = "",
            departingMessages = listOf(Pair("User", "Read this $marker"))
        )
        val withoutMarker = SummarizerPrompts.foldInRequestBody(
            renderedPrompt = "PROMPT",
            existingSummary = "",
            departingMessages = listOf(Pair("User", "Just talking"))
        )

        assertTrue(withMarker.contains(SummarizerPrompts.ATTACHMENT_RULE))
        assertFalse(withoutMarker.contains(SummarizerPrompts.ATTACHMENT_RULE))
    }

    @Test
    fun theAttachmentRuleKeepsTheAnchorWithoutReproducingTheAttachment() {
        val rule = SummarizerPrompts.ATTACHMENT_RULE
        assertTrue(rule.contains("copy its ID exactly"))
        assertTrue(rule.contains("why it mattered"))
        assertTrue(rule.contains("Do not independently summarize or reproduce the attachment"))
        // Facts about the attachment that were actually discussed are ordinary
        // conversation and must still be summarized.
        assertTrue(rule.contains("explicitly discussed in the conversation"))
    }

    @Test
    fun anEmptySummaryIsNamedRatherThanBlank() {
        val body = SummarizerPrompts.foldInRequestBody("PROMPT", "", listOf(Pair("User", "Hi")))
        assertTrue(body.contains("None yet."))
    }

    @Test
    fun theImageSummaryPromptShipsWithInstructions() {
        assertTrue(SummarizerPrompts.IMAGE_SUMMARY.isNotBlank())
        assertTrue(SummarizerPrompts.IMAGE_SUMMARY.contains("1–2 sentences"))
    }

    @Test
    fun imageSummaryBodyCarriesTheInstructionAndThePrompt() {
        val body = SummarizerPrompts.imageSummaryRequestBody(
            instruction = "INSTRUCTION",
            imagePrompt = "a fox beneath glowing mushrooms"
        )
        assertTrue(body.startsWith("INSTRUCTION"))
        assertTrue(body.contains("Image prompt:"))
        assertTrue(body.contains("a fox beneath glowing mushrooms"))
    }
}
