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

/**
 * Renders the enforcer's per-turn memory message from parsed
 * [AssemblyComponents]. Pure Kotlin, unit-tested; the Android side
 * ([Enforcer]) parses the store's JSON into these inputs.
 *
 * The section text is intentionally hardcoded here rather than in string
 * resources: it is a protocol artifact the model receives, not UI copy —
 * localizing it would change what the model reads.
 *
 * Stage 3.4 layout (owner_approved_rules): retrieved memories ("Things you
 * know"), Instruction memories rendered as context rules right after them
 * (law 5 — they activate only because their topic surfaced in retrieval),
 * the user's hand-written lore notes (which outrank everything above), and
 * the scene. Empty sections are omitted entirely — no placeholder text ever
 * reaches the model.
 */
object PromptAssembler {

    // Kept in sync with the lorebook tier's own budget; retrieval_policy may
    // override via "memory_char_budget".
    const val DEFAULT_CHAR_BUDGET = 6000

    private const val PROVENANCE_LEGEND =
        "(told = they said it; observed = seen over time; guessed = tentative — hold guesses lightly, let them go gracefully if wrong)"

    /**
     * Budget rule: when over budget, cut retrieved memories from the
     * lowest-scored up — never protection handling. Lore notes are
     * user-authored and win, so their size is charged against the budget
     * FIRST and memories absorb the entire squeeze.
     * Returns (kept, cut) preserving score-descending order.
     */
    fun applyBudget(
        memories: List<AssembledMemory>,
        loreChars: Int,
        charBudget: Int
    ): Pair<List<AssembledMemory>, List<AssembledMemory>> {
        val available = (charBudget - loreChars).coerceAtLeast(0)
        val kept = ArrayList<AssembledMemory>()
        val cut = ArrayList<AssembledMemory>()
        var used = 0
        // Walk from the highest-scored down; once the budget is exhausted,
        // everything after it is cut (the lowest-scored go first overall).
        for (m in memories.sortedByDescending { it.score }) {
            val cost = m.title.length + m.content.length +
                m.handling.sumOf { it.length } + m.neverAssume.sumOf { it.length }
            if (kept.isNotEmpty() && used + cost > available) {
                cut.add(m)
            } else if (kept.isEmpty() && cost > available) {
                // Even the best memory doesn't fit — inject nothing rather
                // than a truncated memory (handling must never be sheared off).
                cut.add(m)
            } else {
                kept.add(m)
                used += cost
            }
        }
        return kept to cut
    }

    /** The single memory-line renderer. There is deliberately no other place
     *  that turns an [AssembledMemory] into prompt text: a protected memory
     *  can therefore never appear without its HANDLE WITH CARE line. */
    fun renderMemoryLine(m: AssembledMemory): String {
        val sb = StringBuilder()
        sb.append("- (").append(m.provenanceMarker).append(") ")
            .append(m.title.trim()).append(": ").append(m.content.trim())
        if (m.isProtected) {
            sb.append("\n  HANDLE WITH CARE")
            if (m.handling.isNotEmpty()) {
                sb.append(": ").append(m.handling.joinToString("; "))
            }
            sb.append(".")
            if (m.neverAssume.isNotEmpty()) {
                sb.append(" Never assume: ").append(m.neverAssume.joinToString("; ")).append(".")
            }
        }
        return sb.toString()
    }

    /** Renders the enforcer's system message. Empty string = nothing to inject.
     *
     *  Section order (3.6d, cache-aware): the scene — persona presentation +
     *  the always-active Zone 1 cores — renders FIRST because it is the
     *  stable part of this turn-variable message (providers cache up to the
     *  first divergent token, so stable-first extends the reusable prefix);
     *  retrieved memories, rules, lore notes and fired card entries follow. */
    fun render(c: AssemblyComponents): String {
        val sections = ArrayList<String>()

        if (!c.scene.isEmpty) {
            val sb = StringBuilder("## The scene")
            c.scene.userPersonaPresentation?.takeIf { it.isNotBlank() }?.let {
                sb.append("\nYou are appearing to them as they've chosen: ").append(it.trim())
            }
            // Zone 1 cores in the FIXED work-order sequence (the Enforcer
            // builds the list in that order; this renderer never reorders).
            for (core in c.scene.cores) {
                sb.append("\n").append(core.heading)
                for (f in core.fields) {
                    sb.append("\n  ").append(f.label).append(": ").append(f.value.trim())
                }
            }
            c.scene.rosterLine?.takeIf { it.isNotBlank() }?.let { sb.append("\n").append(it) }
            if (c.scene.hasRoleplayCards) {
                sb.append(
                    "\nRemember: the player is still the same person — the character is costume, the fiction stays fiction."
                )
            }
            sections.add(sb.toString())
        }

        // One list in, split here (law 5): an Instruction memory is a context
        // rule the model must follow now that its topic has come up, so it is
        // rendered as a rule, not filed among the facts. The split living in
        // the renderer means the enforcer can never route one to the wrong
        // section.
        val (rules, known) = c.memories.partition { it.isInstruction }

        if (known.isNotEmpty()) {
            val sb = StringBuilder("## Things you know\n").append(PROVENANCE_LEGEND)
            for (m in known) sb.append("\n").append(renderMemoryLine(m))
            sections.add(sb.toString())
        }

        if (rules.isNotEmpty()) {
            val sb = StringBuilder(
                "## Handling rules from the user\nThese apply because their topic has come up — follow them now:"
            )
            for (m in rules) sb.append("\n").append(renderMemoryLine(m))
            sections.add(sb.toString())
        }

        if (c.loreNotes.isNotEmpty()) {
            val sb = StringBuilder(
                "## Hand-written notes from the user (these outrank anything above that disagrees)"
            )
            for (note in c.loreNotes) {
                sb.append("\n- ")
                if (note.label.isNotBlank()) sb.append(note.label.trim()).append(": ")
                sb.append(note.content.trim())
            }
            sections.add(sb.toString())
        }

        if (c.cardEntries.isNotEmpty()) {
            val sb = StringBuilder(
                "## From the story's cards (user-written; these outrank memories that disagree)"
            )
            for (e in c.cardEntries) {
                sb.append("\n- ").append(e.sectionLabel).append(" — ").append(e.name.trim())
                if (e.body.isNotBlank()) sb.append(": ").append(e.body.trim())
                if (e.connectedTo.isNotEmpty()) {
                    sb.append("\n  connected to: ").append(e.connectedTo.joinToString(", "))
                }
            }
            sections.add(sb.toString())
        }

        return sections.joinToString("\n\n")
    }
}
