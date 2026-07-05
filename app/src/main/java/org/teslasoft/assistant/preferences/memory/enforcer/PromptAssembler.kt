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
 * Renders `prompt_assembly_template.md` — the literal system-prompt skeleton —
 * from parsed [AssemblyComponents]. Pure Kotlin, unit-tested; the Android side
 * ([Enforcer]) parses the store's JSON into these inputs.
 *
 * The template's text is intentionally hardcoded here rather than in string
 * resources: it is a protocol artifact the spec requires verbatim, not UI
 * copy — localizing it would change what the model receives.
 *
 * Deviations from the template, decided in the integration plan:
 * - Slot 1 (APP CHARACTER CONFIG) and USER'S STANDING INSTRUCTION are already
 *   the app's stable first system message (prefix caching — never reordered),
 *   and the ACTIVATION PROMPT already rides in chat history by the app's
 *   existing mechanism. This renderer produces only what is NEW: the separate
 *   system message that follows the stable one.
 * - Where a section is empty it is omitted entirely, per the template's rule
 *   that no placeholder text ever reaches the model.
 */
object PromptAssembler {

    // Kept in sync with the lorebook tier's own budget; retrieval_policy may
    // override via "memory_char_budget".
    const val DEFAULT_CHAR_BUDGET = 6000

    private const val PROVENANCE_LEGEND =
        "(told = they said it; observed = seen over time; guessed = tentative — hold guesses lightly, let them go gracefully if wrong)"

    /**
     * Budget rule from the template's assembly rules: when over budget, cut
     * retrieved memories from the lowest-scored up — never hard limits, the
     * standing packet, or protection handling. Always-load memories live in
     * the standing packet, so everything here is cuttable; lore notes are
     * user-authored and win, so their size is charged against the budget
     * FIRST and memories absorb the entire squeeze.
     * Returns (kept, cut) preserving the original (score-descending) order.
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

    /** Renders the enforcer's system message. Empty string = nothing to inject. */
    fun render(c: AssemblyComponents): String {
        val sections = ArrayList<String>()

        c.modelNote?.takeIf { it.isNotBlank() }?.let {
            sections.add("MODEL NOTE: ${it.trim()}")
        }

        if (c.hardLimits.isNotEmpty()) {
            sections.add(
                "Hard limits — these never move, regardless of anything below:\n" +
                    c.hardLimits.joinToString("\n") { it.trim() }
            )
        }

        c.standingPacket?.takeIf { it.isNotBlank() }?.let {
            sections.add("## About the person you're with\n${it.trim()}")
        }

        for (mode in c.modes.take(2)) {
            val sb = StringBuilder("## Right now\n")
            sb.append("This moment calls for ").append(mode.name)
            mode.purpose?.takeIf { it.isNotBlank() }?.let { sb.append(": ").append(it.trim()) }
            if (mode.respond.isNotEmpty()) sb.append("\nDo: ").append(mode.respond.joinToString("; "))
            if (mode.avoid.isNotEmpty()) sb.append("\nDon't: ").append(mode.avoid.joinToString("; "))
            sections.add(sb.toString())
        }

        if (c.memories.isNotEmpty() || c.entitySummaries.isNotEmpty()) {
            val sb = StringBuilder("## Things you know\n").append(PROVENANCE_LEGEND)
            for (m in c.memories) sb.append("\n").append(renderMemoryLine(m))
            for ((name, summary) in c.entitySummaries) {
                sb.append("\n- (observed) About ").append(name.trim()).append(": ").append(summary.trim())
            }
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

        if (!c.scene.isEmpty) {
            val sb = StringBuilder("## The scene")
            c.scene.userPersonaPresentation?.takeIf { it.isNotBlank() }?.let {
                sb.append("\nYou are appearing to them as they've chosen: ").append(it.trim())
            }
            if (!c.scene.worldName.isNullOrBlank()) {
                sb.append("\nWorld: ").append(c.scene.worldName.trim())
                c.scene.worldPremise?.takeIf { it.isNotBlank() }?.let { sb.append(" — ").append(it.trim()) }
                c.scene.worldRules?.takeIf { it.isNotBlank() }?.let { sb.append(" Rules of play: ").append(it.trim()) }
                if (!c.scene.characterName.isNullOrBlank()) {
                    sb.append("\nThey are playing ").append(c.scene.characterName.trim())
                    c.scene.characterDescription?.takeIf { it.isNotBlank() }?.let { sb.append(": ").append(it.trim()) }
                    c.scene.characterArc?.takeIf { it.isNotBlank() }?.let { sb.append(" Story so far: ").append(it.trim()) }
                }
                sb.append(
                    "\nRemember: the player is still the same person — everything in " +
                        "\"About the person you're with\" still applies. The character is costume, the fiction stays fiction."
                )
            }
            sections.add(sb.toString())
        }

        return sections.joinToString("\n\n")
    }
}
