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
 * prompt that mishandles it — the assembly rules are contractual, so they're
 * pinned here on the JVM (pure Kotlin, no Android). Stage 3.4 shape: memories,
 * Instruction memories as context rules, lore notes, scene — nothing else.
 */
class PromptAssemblerTest {

    private fun mem(
        id: String,
        score: Float = 1f,
        handling: List<String> = emptyList(),
        neverAssume: List<String> = emptyList(),
        content: String = "content of $id",
        kind: String = "fact"
    ) = AssembledMemory(
        memoryId = id, title = id, content = content,
        provenanceMarker = "told", handling = handling, neverAssume = neverAssume,
        score = score, kind = kind
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
        val onlyLore = PromptAssembler.render(AssemblyComponents(loreNotes = listOf(LoreNote("N", "text"))))
        assertTrue(onlyLore.contains("## Hand-written notes from the user"))
        assertFalse(onlyLore.contains("## Things you know"))
        assertFalse(onlyLore.contains("## Handling rules"))
        assertFalse(onlyLore.contains("## The scene"))
    }

    @Test
    fun retiredSectionsNeverRender() {
        // Stage 3.4 (owner_approved_rules §15): no standing packet, no modes,
        // no hard-limits render, no model note, no entity summaries — even a
        // fully-populated assembly must not contain their headers.
        val out = PromptAssembler.render(
            AssemblyComponents(
                memories = listOf(mem("m1"), mem("rule", kind = "instruction")),
                loreNotes = listOf(LoreNote("Note", "hand-written fact")),
                scene = SceneContext(cores = listOf(CardCore("World: W", listOf(CoreField("Premise / Vibe", "premise")))))
            )
        )
        assertFalse(out.contains("About the person you're with"))
        assertFalse(out.contains("Right now"))
        assertFalse(out.contains("Hard limits"))
        assertFalse(out.contains("MODEL NOTE"))
        assertFalse(out.contains("Always know"))
    }

    @Test
    fun sectionOrderIsSceneMemoriesRulesLoreCards() {
        // 3.6d, cache-aware: the stable scene/cores render FIRST; the
        // turn-variable material follows; fired card entries close the message.
        val out = PromptAssembler.render(
            AssemblyComponents(
                memories = listOf(mem("m1"), mem("rule", kind = "instruction")),
                loreNotes = listOf(LoreNote("Note", "hand-written fact")),
                scene = SceneContext(cores = listOf(CardCore("World: W", listOf(CoreField("Premise / Vibe", "premise"))))),
                cardEntries = listOf(AssembledCardEntry("ce-1", "Regions", "Verdant Kingdom", "rolling farmland"))
            )
        )
        val order = listOf(
            "## The scene", "## Things you know", "## Handling rules from the user",
            "## Hand-written notes from the user", "## From the story's cards"
        )
        val positions = order.map { out.indexOf(it) }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions, positions.sorted())
    }

    @Test
    fun instructionMemoriesRenderAsRulesNotFacts() {
        val out = PromptAssembler.render(
            AssemblyComponents(
                memories = listOf(
                    mem("fact-mem", content = "a plain fact"),
                    mem("rule-mem", content = "don't pity her when her mom comes up", kind = "instruction")
                )
            )
        )
        val factsSection = out.substringAfter("## Things you know").substringBefore("## Handling rules")
        val rulesSection = out.substringAfter("## Handling rules from the user")
        assertTrue(factsSection.contains("a plain fact"))
        assertFalse(factsSection.contains("don't pity her"))
        assertTrue(rulesSection.contains("don't pity her when her mom comes up"))
        assertTrue(rulesSection.contains("follow them now"))
    }

    @Test
    fun protectedInstructionKeepsItsHandling() {
        // The single-renderer rule holds for rules too: a protected Instruction
        // memory can never render without its HANDLE WITH CARE line.
        val out = PromptAssembler.render(
            AssemblyComponents(
                memories = listOf(mem("rule", kind = "instruction", handling = listOf("tread softly")))
            )
        )
        assertTrue(out.contains("HANDLE WITH CARE: tread softly."))
    }

    @Test
    fun loreNotesOutrankAndSceneKeepsThePersonReal() {
        // 3.6d: the scene carries card CORES (Zone 1, spec field labels) —
        // the dormant pre-card premise/rules/description/arc never render.
        val out = PromptAssembler.render(
            AssemblyComponents(
                loreNotes = listOf(LoreNote("Fact", "the truth")),
                scene = SceneContext(
                    cores = listOf(
                        CardCore("World: Aeldra", listOf(
                            CoreField("Premise / Vibe", "a broken realm"),
                            CoreField("Magic Rules", "no resurrection")
                        )),
                        CardCore("They are playing: Mira", listOf(
                            CoreField("Class", "mage"),
                            CoreField("Goals & Drives", "reach chapter two")
                        ))
                    ),
                    rosterLine = "No longer with the party: Rose — dead"
                )
            )
        )
        assertTrue(out.contains("these outrank anything above that disagrees"))
        assertTrue(out.contains("World: Aeldra"))
        assertTrue(out.contains("  Premise / Vibe: a broken realm"))
        assertTrue(out.contains("  Magic Rules: no resurrection"))
        assertTrue(out.contains("They are playing: Mira"))
        assertTrue(out.contains("No longer with the party: Rose — dead"))
        assertTrue(out.contains("the character is costume, the fiction stays fiction"))
    }

    @Test
    fun cardEntriesRenderWithConnectedToLine() {
        val out = PromptAssembler.render(
            AssemblyComponents(
                cardEntries = listOf(
                    AssembledCardEntry(
                        "ce-1", "Reliquary", "The Silver Key",
                        "held by: Vael; Opens the vault.",
                        connectedTo = listOf("Eclipse Gate", "The Long Dark")
                    )
                )
            )
        )
        assertTrue(out.contains("## From the story's cards"))
        assertTrue(out.contains("- Reliquary — The Silver Key: held by: Vael; Opens the vault."))
        assertTrue(out.contains("  connected to: Eclipse Gate, The Long Dark"))
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
}
