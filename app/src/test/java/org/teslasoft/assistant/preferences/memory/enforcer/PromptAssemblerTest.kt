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
        typeId: String? = null
    ) = AssembledMemory(
        memoryId = id, content = content,
        handling = handling, neverAssume = neverAssume,
        score = score, typeId = typeId
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
        // Plain memory text (Phase 2 review): "- " + content, with no provenance
        // marker and no title prefix.
        assertTrue(line.startsWith("- content of m1"))
        assertFalse("no title prefix before content", line.contains("m1: content"))
    }

    @Test
    fun provenanceNeverAppearsInTheRenderedPrompt() {
        // Review: no (told)/(observed)/(guessed) marker on any line and no legend
        // in the "Things you know" header — provenance is gone from the prompt.
        val out = PromptAssembler.render(
            AssemblyComponents(
                memories = listOf(
                    mem("m1", content = "she prefers tea"),
                    mem("rule", content = "keep replies short", typeId = "mtype-instruction")
                )
            )
        )
        assertTrue(out.contains("## Things you know"))
        assertFalse(out.contains("(told)"))
        assertFalse(out.contains("(observed)"))
        assertFalse(out.contains("(guessed)"))
        assertFalse(out.contains("told = they said it"))
        // Plain memory text is present.
        assertTrue(out.contains("- she prefers tea"))
    }

    @Test
    fun instructionBehaviorIsKeyedOnTheStableTypeIdNotAName() {
        // Renaming the Instruction Type does not change its stable id, so a
        // memory carrying that id still renders as a rule; a memory with any
        // other (or no) type id renders as a fact. The renderer only ever sees
        // the id, never a display name or the legacy kind string.
        val ruleByTrueId = mem("r", content = "no purple prose", typeId = "mtype-instruction")
        val notARule = mem("f", content = "a plain fact", typeId = "instruction") // display-name-like, not the id
        assertTrue(ruleByTrueId.isInstruction)
        assertFalse("only the stable Instruction Type id triggers rule rendering", notARule.isInstruction)
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
                memories = listOf(mem("m1"), mem("rule", typeId = "mtype-instruction")),
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
                memories = listOf(mem("m1"), mem("rule", typeId = "mtype-instruction")),
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
                    mem("rule-mem", content = "don't pity her when her mom comes up", typeId = "mtype-instruction")
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
                memories = listOf(mem("rule", typeId = "mtype-instruction", handling = listOf("tread softly")))
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
    fun memoryCostIsAtomicIncludingProtectionHandling() {
        // The budget cost carries the handling and never-assume lines with
        // the memory, so the enforcer's walk can only ever cut a protected
        // memory WHOLE — handling can never be sheared off separately.
        val protected1 = mem("p", score = 0.9f, handling = listOf("h".repeat(300)))
        assertTrue(PromptAssembler.memoryCost(protected1) >= 300)
        val plain = mem("q", score = 0.9f)
        // Cost is content only now — titles are retired (§3.1).
        assertEquals(
            plain.content.length,
            PromptAssembler.memoryCost(plain)
        )
    }

    @Test
    fun budgetWalkSkipsOversizedMemoriesAndBackfillsSmallerOnes() {
        // The production budget admission: ranked walk, atomic cost, an
        // oversized memory is skipped whole and its slot backfills from
        // lower-ranked candidates (counterplan §10 A.2).
        val available = 120
        var used = 0
        val ranked = listOf(
            mem("big-high", score = 0.9f, content = "x".repeat(200)),
            mem("fits-mid", score = 0.5f, content = "y".repeat(80)),
            mem("fits-low", score = 0.3f, content = "z".repeat(20))
        )
        val cut = ArrayList<String>()
        val selection = RetrievalBackfill.select(ranked, topK = 2) { m ->
            val cost = PromptAssembler.memoryCost(m)
            if (used + cost > available) {
                cut.add(m.memoryId)
                false
            } else {
                used += cost
                true
            }
        }
        assertEquals(listOf("fits-mid", "fits-low"), selection.kept.map { it.memoryId })
        assertEquals(listOf("big-high"), cut)
    }
}
