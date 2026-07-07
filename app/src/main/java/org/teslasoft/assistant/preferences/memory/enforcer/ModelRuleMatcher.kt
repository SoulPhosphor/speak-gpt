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

import org.json.JSONArray

/**
 * Model-string matching for Model rules (owner_approved_rules §11, Stage 4).
 * Drives exactly ONE thing: the "(matches this chat's model)" hint next to a
 * profile in the Quick Settings dropdown — it never selects or applies
 * anything (§11: the hint "never auto-applies"). Phase 6 will reuse it to
 * file drafts into the profile carrying a chat's model string.
 *
 * Rule (work order): case-insensitive contains with the provider prefix
 * ignored — "openai/gpt-4o" and "gpt-4o" are the same model, and a stored
 * snapshot string like "glm-5" matches the chat model "glm-5-0502".
 * Pure Kotlin, unit-tested.
 */
object ModelRuleMatcher {

    /** Lowercase, trimmed, with any provider prefix ("openrouter/…",
     *  "openai/…") dropped — the same model is the same model from any
     *  provider (§11). */
    fun normalize(modelString: String): String =
        modelString.trim().lowercase().substringAfterLast('/')

    /** True when one normalized string contains the other (a profile may
     *  store the family string while the chat runs a dated snapshot, or the
     *  other way around). Blank on either side never matches. */
    fun matches(profileModelString: String, chatModelId: String): Boolean {
        val profile = normalize(profileModelString)
        val chat = normalize(chatModelId)
        if (profile.isEmpty() || chat.isEmpty()) return false
        return chat.contains(profile) || profile.contains(chat)
    }

    /** True when any string in the profile's JSON list matches [chatModelId].
     *  A malformed list simply never matches — the hint is cosmetic and must
     *  never break the picker. */
    fun profileMatchesModel(modelStringsJson: String, chatModelId: String): Boolean {
        if (chatModelId.isBlank()) return false
        return try {
            val arr = JSONArray(modelStringsJson)
            (0 until arr.length()).any { matches(arr.getString(it), chatModelId) }
        } catch (_: Exception) {
            false
        }
    }
}
