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

package org.teslasoft.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests pinning the CURRENT `/imagine` behavior before the
 * image generation rebuild (image-generation-rebuild-plan.md, step 1). Some
 * of the behavior below is a known defect the rebuild will deliberately
 * change (plan section 4.6) — these tests document today's behavior so the
 * later change is a conscious test update, not an accident.
 */
class LegacyImagineCommandTest {

    // --- parseMessage image branch ---

    @Test
    fun commandAtStartWithPromptTriggersGeneration() {
        assertTrue(LegacyImagineCommand.triggersImageGeneration("/imagine a cat", true))
    }

    @Test
    fun promptIsEverythingAfterTheFirstNineCharacters() {
        assertEquals("a cat", LegacyImagineCommand.extractPrompt("/imagine a cat"))
    }

    @Test
    fun detectionIsCaseInsensitive() {
        assertTrue(LegacyImagineCommand.triggersImageGeneration("/IMAGINE a cat", true))
    }

    @Test
    fun disabledSettingSuppressesTheCommand() {
        assertFalse(LegacyImagineCommand.triggersImageGeneration("/imagine a cat", false))
        assertFalse(LegacyImagineCommand.showsEmptyPromptError("/imagine", false))
    }

    @Test
    fun ordinaryMessageDoesNotTrigger() {
        assertFalse(LegacyImagineCommand.triggersImageGeneration("draw me a cat", true))
        assertFalse(LegacyImagineCommand.showsEmptyPromptError("draw me a cat", true))
    }

    /** Known defect (plan 4.6): a mid-text mention of the command still
     *  triggers generation, and the fixed-position slice garbles the prompt. */
    @Test
    fun midTextMentionStillTriggersWithGarbledPrompt() {
        val stored = "we talked about /imagine yesterday"
        assertTrue(LegacyImagineCommand.triggersImageGeneration(stored, true))
        assertEquals(" about /imagine yesterday", LegacyImagineCommand.extractPrompt(stored))
    }

    /** Known defect (plan 4.6): the slice position ignores a chat prefix, so
     *  a prefixed stored message loses the start of its prompt and keeps part
     *  of the command. */
    @Test
    fun chatPrefixGarblesTheExtractedPrompt() {
        val stored = "> /imagine a cat"
        assertTrue(LegacyImagineCommand.triggersImageGeneration(stored, true))
        assertEquals("e a cat", LegacyImagineCommand.extractPrompt(stored))
    }

    // --- parseMessage empty-prompt branch ---

    @Test
    fun bareCommandShowsTheEmptyPromptError() {
        assertTrue(LegacyImagineCommand.showsEmptyPromptError("/imagine", true))
        assertFalse(LegacyImagineCommand.triggersImageGeneration("/imagine", true))
    }

    @Test
    fun commandWithOnlyATrailingSpaceIsStillEmpty() {
        assertTrue(LegacyImagineCommand.showsEmptyPromptError("/imagine ", true))
    }

    @Test
    fun singleCharacterPromptIsEnoughToGenerate() {
        assertTrue(LegacyImagineCommand.triggersImageGeneration("/imagine a", true))
        assertEquals("a", LegacyImagineCommand.extractPrompt("/imagine a"))
    }

    // --- prepareTypedTurn diversion gate ---

    @Test
    fun imagineCommandDivertsToTheLegacyPipeline() {
        assertTrue(
            LegacyImagineCommand.divertsTypedTurnToLegacyPipeline(
                "/imagine a cat", true, "some-model", false
            )
        )
    }

    @Test
    fun imagineMentionAnywhereInTheRawMessageDiverts() {
        assertTrue(
            LegacyImagineCommand.divertsTypedTurnToLegacyPipeline(
                "what does /imagine do?", true, "some-model", false
            )
        )
    }

    @Test
    fun disabledImagineDoesNotDivert() {
        assertFalse(
            LegacyImagineCommand.divertsTypedTurnToLegacyPipeline(
                "/imagine a cat", false, "some-model", false
            )
        )
    }

    @Test
    fun fineTunedModelNamesDivert() {
        assertTrue(
            LegacyImagineCommand.divertsTypedTurnToLegacyPipeline(
                "hello", true, "davinci:ft-personal-2024", false
            )
        )
        assertTrue(
            LegacyImagineCommand.divertsTypedTurnToLegacyPipeline(
                "hello", true, "ft:gpt-tuned", false
            )
        )
    }

    @Test
    fun functionCallingDiverts() {
        assertTrue(
            LegacyImagineCommand.divertsTypedTurnToLegacyPipeline(
                "hello", true, "some-model", true
            )
        )
    }

    @Test
    fun plainChatStaysOnTheTypedSendPath() {
        assertFalse(
            LegacyImagineCommand.divertsTypedTurnToLegacyPipeline(
                "hello", true, "some-model", false
            )
        )
    }
}
