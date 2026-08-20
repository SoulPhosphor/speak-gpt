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
    /* Editable display name shown as the card label. NOT the identity — see [id]. */
    var label: String,
    /* The default persona prompt text. Prepended before the always-on system message.
     * When multiple prompt variants exist, this returns the default variant's text.
     * Setting this updates the default variant (or the single legacy prompt). */
    var prompt: String = "",
    /* All prompt variants for this companion. Empty list means a legacy single-prompt
     * companion (use [prompt] directly). Populated from the _prompt_variants
     * SharedPreferences key; missing key falls back to a single variant from [prompt]. */
    var promptVariants: ArrayList<CompanionPromptVariant> = arrayListOf(),
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
    var lastUsedLoreBookIds: String = "",
    /* Bare hash of the assigned Profile Image (companion picture), or "" for
     * none. The catalog/files live in profile_images.db; this only references.
     * Kept attached to the same companion across a rename via the stable [id]. */
    var avatarRef: String = "",
    /* Stable identity of this companion. Minted ONCE at creation and never
     * recomputed from [label], so renaming keeps every reference valid (per-chat
     * persona_id, last-used companion, avatar, activation prompt, the memory
     * store's companion record). Empty only for a brand-new, not-yet-saved
     * object; [PersonaPreferences.setPersona] assigns one on first save. Existing
     * companions keep their original hashed id (the preference key). */
    var id: String = "",
    /* Optional chat-name typography overrides. Empty/zero means inherit the
     * Appearance AI-name default; the two values may be overridden separately. */
    var chatNameFontId: String = "",
    var chatNameSizeSp: Int = 0
) {
    /** Parsed view of [additionalLoreBookIds]. */
    fun additionalLoreBookIdList(): ArrayList<String> = splitIds(additionalLoreBookIds)

    /** Parsed view of [lastUsedLoreBookIds]. */
    fun lastUsedLoreBookIdList(): ArrayList<String> = splitIds(lastUsedLoreBookIds)

    companion object {
        /**
         * Brand-new companion draft for the multi-prompt editor. The editor's tab UI
         * requires at least one variant, so creation must never use the legacy
         * empty-variant representation. The blank Prompt 1 is intentionally unsaved
         * until the owner saves the companion.
         */
        fun emptyDraft(): PersonaObject {
            return PersonaObject(
                label = "",
                prompt = "",
                promptVariants = ArrayList(CompanionPromptVariant.migrateFromSinglePrompt(""))
            )
        }

        fun splitIds(joined: String): ArrayList<String> {
            return ArrayList(joined.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        }

        fun joinIds(ids: List<String>): String {
            return ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
        }
    }
}
