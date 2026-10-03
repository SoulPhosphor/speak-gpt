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
import org.teslasoft.assistant.preferences.dto.CompanionPromptVariant
import org.teslasoft.assistant.util.summarizer.SummarizerPromptSets.Kind

class SummarizerPromptSetsTest {

    private fun legacy(
        selected: Int = 0,
        recency: String = "",
        names: Map<Int, String> = emptyMap(),
        prompts: Map<Int, String> = emptyMap()
    ) = SummarizerPromptSets.fromLegacySlots(
        selectedSlot = selected,
        recencyCsv = recency,
        slotName = { names[it].orEmpty() },
        slotPrompt = { prompts[it].orEmpty() }
    )

    @Test
    fun untouchedOlderSettingsBecomeTheTwoShippedPromptsWithStorytellerInUse() {
        val variants = legacy()
        assertEquals(listOf(SummarizerPromptSets.STORYTELLER_ID, SummarizerPromptSets.REPORTER_ID), variants.map { it.id })
        assertEquals(SummarizerPrompts.STORYTELLER, variants[0].text)
        assertEquals(SummarizerPrompts.REPORTER, variants[1].text)
        assertEquals(SummarizerPrompts.STORYTELLER, SummarizerPromptSets.activeTextOf(Kind.SUMMARY, variants))
    }

    @Test
    fun customSlotsNamesEditsAndTheSelectedSlotCarryOver() {
        val variants = legacy(
            selected = 3,
            names = mapOf(1 to "Notes", 3 to "Story beats"),
            prompts = mapOf(0 to "Edited storyteller", 3 to "Keep the beats.")
        )
        assertEquals(3, variants.size)
        assertEquals("Edited storyteller", variants[0].text)
        assertEquals("Notes", variants[1].name)
        assertEquals("Story beats", variants[2].name)
        assertEquals("Keep the beats.", SummarizerPromptSets.activeTextOf(Kind.SUMMARY, variants))
        assertEquals(1, variants.count { it.isDefault })
    }

    @Test
    fun anEmptySelectedSlotFallsBackThroughRecencyLikeTheOlderSettings() {
        val variants = legacy(selected = 4, recency = "4,1,0")
        assertEquals(SummarizerPrompts.REPORTER, SummarizerPromptSets.activeTextOf(Kind.SUMMARY, variants))
    }

    @Test
    fun aBlankInUsePromptFallsBackToTheFirstBuiltIn() {
        val variants = listOf(
            CompanionPromptVariant(id = "x", name = "Empty", text = "  ", isDefault = true)
        )
        assertEquals(SummarizerPrompts.STORYTELLER, SummarizerPromptSets.activeTextOf(Kind.COMPACTION, variants))
        assertEquals(SummarizerPrompts.IMAGE_SUMMARY, SummarizerPromptSets.activeTextOf(Kind.IMAGE, variants))
    }

    @Test
    fun normalizingRestoresMissingBuiltInsAndKeepsExactlyOneInUse() {
        val custom = CompanionPromptVariant(id = "c", name = "Mine", text = "Mine.", isDefault = true)
        val normalized = SummarizerPromptSets.normalize(Kind.SUMMARY, listOf(custom))
        assertEquals(
            listOf(SummarizerPromptSets.STORYTELLER_ID, SummarizerPromptSets.REPORTER_ID, "c"),
            normalized.map { it.id }
        )
        assertEquals(listOf(false, false, true), normalized.map { it.isDefault })

        val noneInUse = SummarizerPromptSets.normalize(
            Kind.IMAGE,
            SummarizerPromptSets.builtIns(Kind.IMAGE).map { it.copy(isDefault = false) }
        )
        assertTrue(noneInUse.single().isDefault)
    }

    @Test
    fun builtInsKeepTheirShippedNamesAndOriginalText() {
        val renamed = SummarizerPromptSets.builtIns(Kind.SUMMARY).map { it.copy(name = "Renamed", text = "Edited.") }
        val normalized = SummarizerPromptSets.normalize(Kind.SUMMARY, renamed)
        assertEquals(listOf(SummarizerPrompts.STORYTELLER_NAME, SummarizerPrompts.REPORTER_NAME), normalized.map { it.name })
        assertEquals(listOf("Edited.", "Edited."), normalized.map { it.text })
        assertEquals(SummarizerPrompts.STORYTELLER, SummarizerPromptSets.originalText(Kind.COMPACTION, SummarizerPromptSets.STORYTELLER_ID))
        assertEquals(SummarizerPrompts.IMAGE_SUMMARY, SummarizerPromptSets.originalText(Kind.IMAGE, SummarizerPromptSets.IMAGE_SUMMARY_ID))
        assertEquals(null, SummarizerPromptSets.originalText(Kind.SUMMARY, "legacy_slot_2"))
    }

    @Test
    fun builtInsAreRecognizedPerCollection() {
        assertTrue(SummarizerPromptSets.isBuiltIn(Kind.SUMMARY, SummarizerPromptSets.STORYTELLER_ID))
        assertTrue(SummarizerPromptSets.isBuiltIn(Kind.COMPACTION, SummarizerPromptSets.REPORTER_ID))
        assertTrue(SummarizerPromptSets.isBuiltIn(Kind.IMAGE, SummarizerPromptSets.IMAGE_SUMMARY_ID))
        assertFalse(SummarizerPromptSets.isBuiltIn(Kind.IMAGE, SummarizerPromptSets.STORYTELLER_ID))
        assertFalse(SummarizerPromptSets.isBuiltIn(Kind.SUMMARY, "legacy_slot_2"))
    }

    @Test
    fun aCustomOlderImagePromptStaysInUseBesideTheBuiltIn() {
        assertEquals(
            listOf(SummarizerPromptSets.IMAGE_SUMMARY_ID),
            SummarizerPromptSets.fromLegacyImagePrompt("").map { it.id }
        )
        val carried = SummarizerPromptSets.fromLegacyImagePrompt("Two lines, please.")
        assertEquals(2, carried.size)
        assertEquals("Two lines, please.", SummarizerPromptSets.activeTextOf(Kind.IMAGE, carried))
    }

    @Test
    fun aQuickSettingsChoiceWinsWhileItStillExists() {
        val variants = legacy(prompts = mapOf(2 to "Custom."))
        val custom = variants.single { it.id == "legacy_slot_2" }
        assertEquals("Custom.", SummarizerPromptSets.activeTextOf(Kind.SUMMARY, variants, custom.id))
        assertEquals(2, SummarizerPromptSets.selectedIndex(variants, custom.id))
        // A deleted choice falls back to the default prompt.
        assertEquals(SummarizerPrompts.STORYTELLER, SummarizerPromptSets.activeTextOf(Kind.SUMMARY, variants, "gone"))
        assertEquals(0, SummarizerPromptSets.selectedIndex(variants, null))
    }

    @Test
    fun sessionChoicesAreHeldPerChatAndResetWhenTheChatScreenOpens() {
        SummarizerPromptSession.reset("chat-a")
        SummarizerPromptSession.choose("chat-a", Kind.COMPACTION, SummarizerPromptSets.REPORTER_ID)
        assertEquals(SummarizerPromptSets.REPORTER_ID, SummarizerPromptSession.chosenId("chat-a", Kind.COMPACTION))
        assertEquals(null, SummarizerPromptSession.chosenId("chat-a", Kind.SUMMARY))
        assertEquals(null, SummarizerPromptSession.chosenId("chat-b", Kind.COMPACTION))
        SummarizerPromptSession.reset("chat-a")
        assertEquals(null, SummarizerPromptSession.chosenId("chat-a", Kind.COMPACTION))
    }
}
