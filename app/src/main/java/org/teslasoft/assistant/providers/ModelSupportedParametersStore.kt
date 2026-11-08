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

package org.teslasoft.assistant.providers

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder

/**
 * The request parameters each model accepts, as the service reports them
 * (OpenRouter's catalog lists `supported_parameters` per model). Learned
 * whenever a catalog arrives, kept on this device only, and replaced by the
 * next catalog for the same API profile. Nothing here is written by hand: a
 * model the service has not described has no entry, which means "unknown".
 */
class ModelSupportedParametersStore internal constructor(private val prefs: SharedPreferences) {

    companion object {
        private const val FILE = "model_supported_parameters"

        @Volatile private var instance: ModelSupportedParametersStore? = null

        fun get(context: Context): ModelSupportedParametersStore =
            instance ?: synchronized(this) {
                instance ?: ModelSupportedParametersStore(
                    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                ).also { instance = it }
            }

        /** model id to its lower-cased supported parameters, for every catalog
         *  entry that reports a list. Null when the body is not a catalog. */
        internal fun parseCatalog(body: String?): Map<String, Set<String>>? {
            if (body.isNullOrBlank()) return null
            val root = try {
                JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
            } catch (_: Exception) {
                null
            } ?: return null
            val data = root.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            val out = LinkedHashMap<String, Set<String>>()
            for (element in data) {
                val entry = element?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val id = entry.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: continue
                val params = supportedParameters(entry) ?: continue
                out[id] = params
            }
            return out
        }

        private fun supportedParameters(entry: JsonObject): Set<String>? {
            val array = entry.get("supported_parameters")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            return array.mapNotNull { el ->
                el?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            }.toSet()
        }
    }

    private val gson = Gson()

    /** What [modelId] accepts on [endpointId], or null when unknown. */
    fun supported(endpointId: String, modelId: String): Set<String>? {
        if (endpointId.isBlank() || modelId.isBlank()) return null
        val json = prefs.getString(key(endpointId), null) ?: return null
        return try {
            val obj = JsonParser.parseString(json).asJsonObject
            obj.get(modelId)?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it?.takeIf { e -> e.isJsonPrimitive }?.asString }?.toSet()
        } catch (_: Exception) {
            null
        }
    }

    /** Replaces [endpointId]'s list with what [catalogBody] reports. A body
     *  that is not a catalog leaves the stored list as it was. */
    fun learnFromCatalog(endpointId: String, catalogBody: String?) {
        if (endpointId.isBlank()) return
        val parsed = parseCatalog(catalogBody) ?: return
        prefs.edit().putString(key(endpointId), gson.toJson(parsed.mapValues { it.value.sorted() })).apply()
    }

    /** Forgets [endpointId]'s list (its API profile was deleted). */
    fun clearEndpoint(endpointId: String) {
        prefs.edit().remove(key(endpointId)).apply()
    }

    private fun key(endpointId: String) = "endpoint:" + URLEncoder.encode(endpointId, "UTF-8")
}
