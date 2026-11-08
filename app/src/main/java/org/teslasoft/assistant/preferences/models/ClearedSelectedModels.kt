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
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.DedicatedModelRouting
import org.teslasoft.assistant.preferences.ModelPickerScope

/**
 * Background jobs' selected models that a Clean Up Models check found
 * unavailable and therefore cleared (owner ruling, Nov 8 2026): the user
 * chooses a new one. The latest check's list is kept so the Cleanup screen
 * can say what was cleared; the next check replaces it.
 */
class ClearedSelectedModels internal constructor(private val prefs: SharedPreferences) {

    data class Entry(val scope: ModelPickerScope, val target: ModelIdentity)

    companion object {
        private const val FILE = "model_cleanup_cleared_selections"
        private const val KEY = "entries"

        fun get(context: Context) = ClearedSelectedModels(
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        )

        /** The jobs whose selected model is in [unavailable]. */
        fun findUnavailable(
            selected: Map<ModelPickerScope, ModelIdentity>,
            unavailable: Set<ModelIdentity>
        ): List<Entry> = selected.filter { it.value in unavailable }.map { Entry(it.key, it.value) }
    }

    /** Clears each listed job's model (and its own routing) and remembers the
     *  list for the Cleanup screen, replacing the previous check's. */
    fun clearAndRemember(context: Context, entries: List<Entry>) {
        entries.forEach { entry ->
            val routing = DedicatedModelRouting.of(entry.scope)
            // Only when the job still uses that model.
            if (routing.endpointId(context) == entry.target.endpointId && routing.modelId(context) == entry.target.modelId) {
                routing.clear(context)
                routing.select(context, entry.target.endpointId, "")
            }
        }
        val json = JSONArray().apply {
            entries.forEach {
                put(JSONObject().put("scope", it.scope.name)
                    .put("endpointId", it.target.endpointId).put("modelId", it.target.modelId))
            }
        }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    fun load(): List<Entry> = try {
        val array = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            val item = array.getJSONObject(index)
            val scope = ModelPickerScope.entries.firstOrNull { it.name == item.optString("scope") }
                ?.takeIf { it.isDedicated } ?: return@mapNotNull null
            Entry(scope, ModelIdentity(item.getString("endpointId"), item.getString("modelId")))
        }
    } catch (_: Exception) {
        emptyList()
    }
}
