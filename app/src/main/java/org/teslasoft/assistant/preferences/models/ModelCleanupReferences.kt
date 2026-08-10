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

package org.teslasoft.assistant.preferences.models

import android.content.Context
import org.json.JSONArray
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.memory.MemoryStore

/** Current local references used to reconcile a saved cleanup report. */
data class ModelCleanupReferences(
    val favorites: Set<ModelIdentity>,
    val ruleTargetsByRuleId: Map<String, Set<ModelIdentity>>,
    val legacyRuleTargetCount: Int,
    /** False when local saved data could not be read conclusively. */
    val isComplete: Boolean = true
) {
    val ruleTargets: Set<ModelIdentity> get() = ruleTargetsByRuleId.values.flatten().toSet()
    val allTargets: Set<ModelIdentity> get() = favorites + ruleTargets
}

object ModelCleanupReferencesLoader {
    fun load(context: Context): ModelCleanupReferences {
        var complete = true
        val favorites = try {
            FavoriteModelsPreferences.getPreferences(context).getFavoriteModels()
                .mapNotNull { item ->
                    val endpointId = item["endpointId"].orEmpty()
                    val modelId = item["modelId"].orEmpty()
                    if (endpointId.isBlank() || modelId.isBlank()) null
                    else ModelIdentity(endpointId, modelId)
                }
                .toSet()
        } catch (_: Exception) {
            complete = false
            emptySet()
        }

        val byRule = LinkedHashMap<String, Set<ModelIdentity>>()
        var legacyCount = 0
        if (MemoryStore.isProvisioned(context)) {
            try {
                val store = MemoryStore.getInstance(context)
                store.migrateUnambiguousLegacyModelTargets()
                store.getModelRules().forEach { rule ->
                    byRule[rule.ruleId] = ModelIdentityCodec.decode(rule.modelTargetsJson).toSet()
                    legacyCount += try {
                        JSONArray(rule.modelStringsJson).length()
                    } catch (_: Exception) {
                        0
                    }
                }
            } catch (_: Exception) {
                complete = false
            }
        }
        return ModelCleanupReferences(favorites, byRule, legacyCount, complete)
    }
}
