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
import org.json.JSONObject

/** Single saved report; a new manual scan replaces it rather than keeping history. */
class ModelCleanupReportStore private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "model_cleanup_report",
        Context.MODE_PRIVATE
    )

    fun load(): ModelCleanupReport {
        val raw = preferences.getString(KEY_REPORT, null) ?: return ModelCleanupReport()
        return try {
            val root = JSONObject(raw)
            val unavailable = ModelIdentityCodec.decode(root.optJSONArray("unavailable")?.toString())
                .toSet()
            val unchecked = LinkedHashSet<String>()
            val uncheckedJson = root.optJSONArray("unchecked_endpoints") ?: JSONArray()
            for (index in 0 until uncheckedJson.length()) {
                uncheckedJson.optString(index).takeIf { it.isNotBlank() }?.let(unchecked::add)
            }
            val labels = LinkedHashMap<String, String>()
            val labelsJson = root.optJSONArray("endpoint_labels") ?: JSONArray()
            for (index in 0 until labelsJson.length()) {
                val item = labelsJson.optJSONObject(index) ?: continue
                val id = item.optString("endpoint_id")
                val label = item.optString("label")
                if (id.isNotBlank() && label.isNotBlank()) labels[id] = label
            }
            ModelCleanupReport(
                generatedAtMillis = root.optLong("generated_at_millis", 0L),
                unavailable = unavailable,
                uncheckedEndpointIds = unchecked,
                endpointLabels = labels
            )
        } catch (_: Exception) {
            ModelCleanupReport()
        }
    }

    fun save(report: ModelCleanupReport) {
        val root = JSONObject()
            .put("generated_at_millis", report.generatedAtMillis)
            .put("unavailable", JSONArray().apply {
                report.unavailable.forEach { identity ->
                    put(
                        JSONObject()
                            .put("endpoint_id", identity.endpointId)
                            .put("model_id", identity.modelId)
                    )
                }
            })
            .put("unchecked_endpoints", JSONArray(report.uncheckedEndpointIds.toList()))
            .put("endpoint_labels", JSONArray().apply {
                report.endpointLabels.forEach { (endpointId, label) ->
                    put(JSONObject().put("endpoint_id", endpointId).put("label", label))
                }
            })
        preferences.edit().putString(KEY_REPORT, root.toString()).apply()
    }

    companion object {
        private const val KEY_REPORT = "latest_report"

        @Volatile private var instance: ModelCleanupReportStore? = null

        fun get(context: Context): ModelCleanupReportStore = instance ?: synchronized(this) {
            instance ?: ModelCleanupReportStore(context).also { instance = it }
        }
    }
}
