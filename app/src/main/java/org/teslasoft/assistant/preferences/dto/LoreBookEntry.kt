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

package org.teslasoft.assistant.preferences.dto

/**
 * A single lorebook memory entry.
 *
 * This is Phase 1 of the memory system: a memory has some content that gets
 * injected into the prompt when one of its triggers is found in the user's
 * message. Everything here is backed by SQLite (see LoreBookStore) so later
 * phases (text search, vector memory, sync) can build on the same rows.
 */
data class LoreBookEntry(
    /* Stable unique id (UUID). Generated once and never reused, so exports and
     * future sync can reference the same memory across devices. */
    var id: String = "",

    /* Id of the lorebook this memory belongs to. Memories are always scoped to a
     * single lorebook; matching only considers the chat's active lorebook. */
    var lorebookId: String = "",

    /* Friendly name shown in the management list. */
    var label: String = "",

    /* The memory text that is injected into the prompt when a trigger matches. */
    var content: String = "",

    /* The original, unedited source text the memory was created from. Kept so
     * later phases can re-derive embeddings or re-summarize without losing the
     * raw input. May be empty. */
    var sourceText: String = "",

    /* Trigger words or phrases. A match against any of these (case insensitive,
     * substring) injects the memory. */
    var triggers: ArrayList<String> = arrayListOf(),

    /* Whether this memory participates in matching. Disabled memories are kept
     * but never injected. */
    var enabled: Boolean = true,

    /* Epoch millis. */
    var createdAt: Long = 0L,
    var updatedAt: Long = 0L
)
