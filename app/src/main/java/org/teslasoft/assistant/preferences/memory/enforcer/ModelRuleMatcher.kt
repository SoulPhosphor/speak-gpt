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
import org.teslasoft.assistant.preferences.models.ModelIdentity
import org.teslasoft.assistant.preferences.models.ModelIdentityCodec

/**
 * Local Model Rule matching. New targets are exact endpoint/model identities.
 * The old fuzzy matcher remains isolated here only for preserved legacy
 * strings, which the editor labels and lets the user replace.
 */
object ModelRuleMatcher {

    fun exactTargetsMatch(
        modelTargetsJson: String,
        endpointId: String,
        modelId: String
    ): Boolean {
        if (endpointId.isBlank() || modelId.isBlank()) return false
        return ModelIdentity(endpointId, modelId) in ModelIdentityCodec.decode(modelTargetsJson)
    }

    /** Lowercase, trimmed, with any provider prefix ("openrouter/…",
     *  "openai/…") dropped — the same model is the same model from any
     *  provider (§11). */
    fun normalize(modelString: String): String =
        modelString.trim().lowercase().substringAfterLast('/')

    /** True when one normalized string contains the other (a profile may
     *  store the family string while the chat runs a dated snapshot, or the
     *  other way around). Blank on either side never matches. */
    fun legacyMatches(profileModelString: String, chatModelId: String): Boolean {
        val profile = normalize(profileModelString)
        val chat = normalize(chatModelId)
        if (profile.isEmpty() || chat.isEmpty()) return false
        return chat.contains(profile) || profile.contains(chat)
    }

    /** True when any string in the profile's JSON list matches [chatModelId].
     *  A malformed list simply never matches — the hint is cosmetic and must
     *  never break the picker. */
    fun legacyListMatches(modelStringsJson: String, chatModelId: String): Boolean {
        if (chatModelId.isBlank()) return false
        return try {
            val arr = JSONArray(modelStringsJson)
            (0 until arr.length()).any { legacyMatches(arr.getString(it), chatModelId) }
        } catch (_: Exception) {
            false
        }
    }

    fun ruleMatches(
        modelTargetsJson: String,
        legacyModelStringsJson: String,
        endpointId: String,
        modelId: String
    ): Boolean = exactTargetsMatch(modelTargetsJson, endpointId, modelId) ||
        legacyListMatches(legacyModelStringsJson, modelId)
}
