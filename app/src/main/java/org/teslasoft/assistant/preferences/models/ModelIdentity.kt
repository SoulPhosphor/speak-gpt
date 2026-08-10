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

import org.json.JSONArray
import org.json.JSONObject

/**
 * The stable identity of one model as served by one saved API endpoint.
 * Model ids are deliberately case-sensitive and never normalized: they are
 * the exact ids returned by that endpoint's model catalog.
 */
data class ModelIdentity(
    val endpointId: String,
    val modelId: String
)

/** Compact JSON storage for exact endpoint/model identities. */
object ModelIdentityCodec {

    fun decode(json: String?): List<ModelIdentity> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val endpointId = item.optString("endpoint_id")
                    val modelId = item.optString("model_id")
                    if (endpointId.isNotBlank() && modelId.isNotBlank()) {
                        add(ModelIdentity(endpointId, modelId))
                    }
                }
            }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encode(identities: Collection<ModelIdentity>): String {
        val array = JSONArray()
        identities
            .filter { it.endpointId.isNotBlank() && it.modelId.isNotBlank() }
            .distinct()
            .forEach { identity ->
                array.put(
                    JSONObject()
                        .put("endpoint_id", identity.endpointId)
                        .put("model_id", identity.modelId)
                )
            }
        return array.toString()
    }
}

data class LegacyModelTargetResolution(
    val resolved: List<ModelIdentity>,
    val unresolved: List<String>
)

/** Conservative, local-only conversion of pre-Revision-6 model strings. */
object LegacyModelTargetResolver {
    fun resolve(
        legacyModelStrings: Collection<String>,
        knownIdentities: Collection<ModelIdentity>
    ): LegacyModelTargetResolution {
        val resolved = ArrayList<ModelIdentity>()
        val unresolved = ArrayList<String>()
        legacyModelStrings.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { legacy ->
            val matches = knownIdentities.distinct().filter { it.modelId == legacy }
            if (matches.size == 1) resolved.add(matches.single())
            else unresolved.add(legacy)
        }
        return LegacyModelTargetResolution(resolved.distinct(), unresolved)
    }
}
