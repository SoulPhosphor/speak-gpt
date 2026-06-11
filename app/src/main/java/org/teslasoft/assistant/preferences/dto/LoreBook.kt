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
 * A lorebook: a named collection of memories. The app can hold many lorebooks
 * (e.g. one per role-play setting or project), and a chat injects from the single
 * lorebook selected as active for that chat.
 */
data class LoreBook(
    /* Stable unique id (UUID). */
    var id: String = "",

    /* Display name, e.g. "Eldoria Campaign". */
    var name: String = "",

    /* Optional longer description of what this lorebook is for. */
    var description: String = "",

    /* Optional single tag categorizing the book (e.g. "Characters", "World").
     * Used by the lorebook list to filter by type. */
    var tag: String = "",

    /* Epoch millis. */
    var createdAt: Long = 0L,
    var updatedAt: Long = 0L
)
