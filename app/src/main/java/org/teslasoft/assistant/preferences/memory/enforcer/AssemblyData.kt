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
    /** protection.suggested_mode — activates that mode directly when retrieved. */
    val suggestedMode: String? = null
) {
    val isProtected: Boolean get() = handling.isNotEmpty() || neverAssume.isNotEmpty()
}

/** An active mode block (at most two per turn). */
data class ModeBlock(
    val modeId: String,
    val name: String,
    val purpose: String?,
    val respond: List<String>,
    val avoid: List<String>
)

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
 * persona prompt (slot 1, APP CHARACTER CONFIG) and the user's standing
 * instruction, and the activation prompt (slot 6) rides in the chat history
 * by the app's existing mechanism — re-injecting any of them here would
 * duplicate content and break provider prefix caching.
 */
data class AssemblyComponents(
    val modelNote: String? = null,
    val hardLimits: List<String> = emptyList(),
    /** Compressed (or raw-fallback) standing packet; null when the store has nothing. */
    val standingPacket: String? = null,
    val modes: List<ModeBlock> = emptyList(),
    val memories: List<AssembledMemory> = emptyList(),
    /** Entity name -> living summary, rendered once each after the memories. */
    val entitySummaries: Map<String, String> = emptyMap(),
    val loreNotes: List<LoreNote> = emptyList(),
    val scene: SceneContext = SceneContext()
)
