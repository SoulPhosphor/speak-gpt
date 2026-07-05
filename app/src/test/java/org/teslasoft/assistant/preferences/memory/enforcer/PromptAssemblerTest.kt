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

package org.teslasoft.assistant.preferences.memory.enforcer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The enforcer's renderer is what stands between a protected memory and a
 * prompt that mishandles it — the template's assembly rules are contractual,
 * so they're pinned here on the JVM (pure Kotlin, no Android).
 */
class PromptAssemblerTest {

    private fun mem(
        id: String,
        score: Float = 1f,
        handling: List<String> = emptyList(),
        neverAssume: List<String> = emptyList(),
        content: String = "content of $id"
    ) = AssembledMemory(
        memoryId = id, title = id, content = content,
        provenanceMarker = "told", handling = handling, neverAssume = neverAssume,
        score = score
    )

    @Test
    fun protectedMemoryAlwaysCarriesHandlingInline() {
        val line = PromptAssembler.renderMemoryLine(
            mem("m1", handling = listOf("go gently", "never push"), neverAssume = listOf("that it's resolved"))
        )
        assertTrue(line.contains("HANDLE WITH CARE: go gently; never push."))
        assertTrue(line.contains("Never assume: that it's resolved."))
        // The handling is on the SAME rendered unit as the content.
        assertTrue(line.indexOf("content of m1") < line.indexOf("HANDLE WITH CARE"))
    }

    @Test
    fun unprotectedMemoryHasNoHandlingLine() {
        val line = PromptAssembler.renderMemoryLine(mem("m1"))
        assertFalse(line.contains("HANDLE WITH CARE"))
        assertTrue(line.startsWith("- (told) m1: content of m1"))
    }

    @Test
    fun emptySectionsAreOmittedEntirely() {
        val out = PromptAssembler.render(AssemblyComponents())
        assertEquals("", out)
        val onlyLimits = PromptAssembler.render(AssemblyComponents(hardLimits = listOf("Never X.")))
        assertTrue(onlyLimits.contains("Hard limits"))
        assertFalse(onlyLimits.contains("## About the person"))
        assertFalse(onlyLimits.contains("## Things you know"))
        assertFalse(onlyLimits.contains("## The scene"))
        assertFalse(onlyLimits.contains("MODEL NOTE"))
    }

    @Test
    fun sectionOrderFollowsTheTemplate() {
        val out = PromptAssembler.render(
            AssemblyComponents(
                modelNote = "keep it terse",
                hardLimits = listOf("Never X."),
                standingPacket = "They are a whole person.",
                modes = listOf(ModeBlock("m", "Steady Presence", "be calm", listOf("stay"), listOf("panic"))),
                memories = listOf(mem("m1")),
                loreNotes = listOf(LoreNote("Note", "hand-written fact")),
                scene = SceneContext(worldName = "W", worldPremise = "premise")
            )
        )
        val order = listOf(
            "MODEL NOTE", "Hard limits", "## About the person you're with",
            "## Right now", "## Things you know",
            "## Hand-written notes from the user", "## The scene"
        )
        val positions = order.map { out.indexOf(it) }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions, positions.sorted())
    }

    @Test
    fun loreNotesOutrankAndSceneKeepsThePersonReal() {
        val out = PromptAssembler.render(
            AssemblyComponents(
                loreNotes = listOf(LoreNote("Fact", "the truth")),
                scene = SceneContext(
                    worldName = "Aeldra", worldPremise = "a broken realm", worldRules = "no resurrection",
                    characterName = "Mira", characterDescription = "a quiet mage", characterArc = "chapter two"
                )
            )
        )
        assertTrue(out.contains("these outrank anything above that disagrees"))
        assertTrue(out.contains("World: Aeldra — a broken realm Rules of play: no resurrection"))
        assertTrue(out.contains("They are playing Mira: a quiet mage Story so far: chapter two"))
        assertTrue(out.contains("The character is costume, the fiction stays fiction."))
    }

    @Test
    fun atMostTwoModeBlocksRender() {
        val out = PromptAssembler.render(
            AssemblyComponents(
                modes = listOf(
                    ModeBlock("a", "A", null, emptyList(), emptyList()),
                    ModeBlock("b", "B", null, emptyList(), emptyList()),
                    ModeBlock("c", "C", null, emptyList(), emptyList())
                )
            )
        )
        assertTrue(out.contains("This moment calls for A"))
        assertTrue(out.contains("This moment calls for B"))
        assertFalse(out.contains("This moment calls for C"))
    }

    @Test
    fun budgetCutsLowestScoredFirstAndLoreChargesFirst() {
        val big = "x".repeat(100)
        val memories = listOf(
            mem("low", score = 0.1f, content = big),
            mem("high", score = 0.9f, content = big),
            mem("mid", score = 0.5f, content = big)
        )
        // Budget fits two memories after lore's share.
        val (kept, cut) = PromptAssembler.applyBudget(memories, loreChars = 50, charBudget = 270)
        assertEquals(listOf("high", "mid"), kept.map { it.memoryId })
        assertEquals(listOf("low"), cut.map { it.memoryId })
    }

    @Test
    fun budgetNeverShearsHandlingOffAProtectedMemory() {
        val protected1 = mem("p", score = 0.9f, handling = listOf("h".repeat(300)))
        // The memory + its handling exceed the budget together: the whole
        // memory is cut, never the handling alone.
        val (kept, cut) = PromptAssembler.applyBudget(listOf(protected1), loreChars = 0, charBudget = 100)
        assertTrue(kept.isEmpty())
        assertEquals(listOf("p"), cut.map { it.memoryId })
    }

    @Test
    fun entitySummariesRenderOnceInsideThingsYouKnow() {
        val out = PromptAssembler.render(
            AssemblyComponents(
                memories = listOf(mem("m1")),
                entitySummaries = linkedMapOf("Project X" to "a long-running build")
            )
        )
        assertTrue(out.contains("- (observed) About Project X: a long-running build"))
    }
}
