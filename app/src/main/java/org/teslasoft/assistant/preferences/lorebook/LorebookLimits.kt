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

package org.teslasoft.assistant.preferences.lorebook

/**
 * The lorebook entry and character limits, one set for every way of sending
 * (owner decision, Stage 2):
 *  - Maximum Lorebook Entries (Lorebook Settings): 1 to 99, default 20. There
 *    is no unlimited setting.
 *  - Maximum Lorebook Characters (Lorebook Settings): optional, off by
 *    default, 1 to 9,999. It applies only while its toggle is on AND a valid
 *    number is entered; otherwise no character limit applies.
 *  - The RAG Active Memory Settings override: while saved memories are used in
 *    the chat, its own Maximum Lorebook Entries replaces the normal one, but
 *    only while its toggle is on AND a valid number is entered.
 * Lorebook text never uses the saved memories' Memory Space.
 */
object LorebookLimits {
    const val DEFAULT_MAX_ENTRIES = 20
    const val MIN_ENTRIES = 1
    const val MAX_ENTRIES = 99
    const val MIN_CHARS = 1
    const val MAX_CHARS = 9_999

    data class Settings(
        val maxEntries: Int = DEFAULT_MAX_ENTRIES,
        val characterLimitEnabled: Boolean = false,
        val maxCharacters: Int? = null,
        val memoryOverrideEnabled: Boolean = false,
        val memoryOverrideEntries: Int? = null
    )

    data class Limits(val maxEntries: Int, val maxCharacters: Int?)

    fun validEntries(value: Int?): Int? = value?.takeIf { it in MIN_ENTRIES..MAX_ENTRIES }

    fun validCharacters(value: Int?): Int? = value?.takeIf { it in MIN_CHARS..MAX_CHARS }

    /** [memoryActive]: saved memories are searched for this chat's messages. */
    fun resolve(settings: Settings, memoryActive: Boolean): Limits {
        val normal = validEntries(settings.maxEntries) ?: DEFAULT_MAX_ENTRIES
        val override = if (memoryActive && settings.memoryOverrideEnabled) {
            validEntries(settings.memoryOverrideEntries)
        } else null
        val characters = if (settings.characterLimitEnabled) validCharacters(settings.maxCharacters) else null
        return Limits(override ?: normal, characters)
    }

    fun select(matches: List<LoreBookMatch>, limits: Limits): LoreBookBudget.Selection =
        LoreBookBudget.select(matches, limits.maxEntries, limits.maxCharacters)
}
