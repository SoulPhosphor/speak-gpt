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
 * Pure inputs to [PromptAssembler]. Everything here is plain data with JSON
 * already parsed into lists by the Android-side [Enforcer], so the renderer
 * (and its unit tests) never touch org.json or a Context.
 *
 * Stage 3.4 (owner_approved_rules, July 6 2026) stripped this down to what the
 * rules actually allow into a prompt: retrieved memories (with Instruction
 * memories rendered as context rules), the user's hand-written lore notes, and
 * the scene. The retired sections — model-adaptation note, hard-limits render,
 * standing packet (owner portrait + directives), modes, entity summaries — are
 * gone: always-on material is card/system-prompt text the user writes (law 4),
 * what the system knows about the user is ordinary memories retrieved by
 * relevance (§15), and people in the user's life are memories, not entities.
 */

/**
 * One memory as it will be rendered into the "Things you know" section.
 * A protected memory carries its handling/neverAssume lists ON the value the
 * renderer receives — there is no way to render one without the other
 * (the spec's "structurally impossible" requirement).
 */
data class AssembledMemory(
    val memoryId: String,
    val title: String,
    val content: String,
    /** told | observed | guessed — the one-word provenance marker. */
    val provenanceMarker: String,
    val handling: List<String> = emptyList(),
    val neverAssume: List<String> = emptyList(),
    /** Librarian score; budget cuts remove the lowest first. */
    val score: Float = 0f,
    val similarity: Float = 0f,
    /** The §5 Type. 'instruction' renders as a context rule (law 5), not a fact. */
    val kind: String = "fact"
) {
    val isProtected: Boolean get() = handling.isNotEmpty() || neverAssume.isNotEmpty()
    val isInstruction: Boolean get() = kind.equals("instruction", ignoreCase = true)
}

/** A matched lore entry — the user's hand-written notes tier. */
data class LoreNote(
    val label: String,
    val content: String
)

/** The scene: user persona presentation (any chat) + world session context. */
data class SceneContext(
    val userPersonaPresentation: String? = null,
    val worldName: String? = null,
    val worldPremise: String? = null,
    val worldRules: String? = null,
    val characterName: String? = null,
    val characterDescription: String? = null,
    val characterArc: String? = null
) {
    val isEmpty: Boolean
        get() = userPersonaPresentation.isNullOrBlank() && worldName.isNullOrBlank()
}

/**
 * Everything the renderer needs for one turn. Slots already owned by the app
 * are deliberately absent: the stable first system message carries the
 * persona prompt and the user's standing instruction (including any hard
 * limits the user wrote onto the card), and the activation prompt rides in
 * the chat history by the app's existing mechanism — re-injecting any of them
 * here would duplicate content and break provider prefix caching.
 * [memories] holds every retrieved memory including Instruction-kind rows;
 * the renderer splits the rules out itself so the two can never disagree
 * about which memory went where.
 */
data class AssemblyComponents(
    val memories: List<AssembledMemory> = emptyList(),
    val loreNotes: List<LoreNote> = emptyList(),
    val scene: SceneContext = SceneContext()
)
