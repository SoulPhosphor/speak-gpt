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

class PersonaObject(
    /* Friendly name. Used as the unique identifier (hashed) and shown as the card label. */
    var label: String,
    /* The persona prompt. Prepended before the always-on system message. */
    var prompt: String = "",
    /* Hashed id of the associated activation prompt, or "" for None (always available). */
    var activationPromptId: String = "",
    /* Lorebook always active while this persona is used, or "" for none. */
    var coreLoreBookId: String = "",
    /* Comma-separated lorebook ids linked to this persona. These appear as
     * checkable options in a chat's Quick Settings (the core book does not —
     * it is always on). No cap on how many can be linked. */
    var additionalLoreBookIds: String = "",
    /* When true, a brand-new chat with this persona starts with the
     * last-used additional lorebooks already checked. When false, new chats
     * start with no additional lorebooks active. */
    var autoLoadLastLoreBooks: Boolean = false,
    /* Comma-separated ids of the additional lorebooks that were checked the
     * last time a chat with this persona changed its selection. Bookkeeping
     * for autoLoadLastLoreBooks; not edited directly by the user. */
    var lastUsedLoreBookIds: String = ""
) {
    /** Parsed view of [additionalLoreBookIds]. */
    fun additionalLoreBookIdList(): ArrayList<String> = splitIds(additionalLoreBookIds)

    /** Parsed view of [lastUsedLoreBookIds]. */
    fun lastUsedLoreBookIdList(): ArrayList<String> = splitIds(lastUsedLoreBookIds)

    companion object {
        fun splitIds(joined: String): ArrayList<String> {
            return ArrayList(joined.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        }

        fun joinIds(ids: List<String>): String {
            return ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
        }
    }
}
