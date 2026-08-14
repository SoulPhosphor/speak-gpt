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

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CompanionPromptVariant(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var text: String,
    var isDefault: Boolean
) {
    companion object {
        fun toJson(variants: List<CompanionPromptVariant>): String {
            val arr = JSONArray()
            for (v in variants) {
                val obj = JSONObject()
                obj.put("id", v.id)
                obj.put("name", v.name)
                obj.put("text", v.text)
                obj.put("isDefault", v.isDefault)
                arr.put(obj)
            }
            return arr.toString()
        }

        fun fromJson(json: String): List<CompanionPromptVariant> {
            if (json.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                val list = mutableListOf<CompanionPromptVariant>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val id = obj.optString("id", "").ifBlank { UUID.randomUUID().toString() }
                    list.add(
                        CompanionPromptVariant(
                            id = id,
                            name = obj.optString("name", ""),
                            text = obj.optString("text", ""),
                            isDefault = obj.optBoolean("isDefault", false)
                        )
                    )
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun migrateFromSinglePrompt(prompt: String, personaId: String = ""): List<CompanionPromptVariant> {
            val id = if (personaId.isNotBlank()) {
                UUID.nameUUIDFromBytes("${personaId}_prompt_1".toByteArray()).toString()
            } else {
                UUID.randomUUID().toString()
            }
            return listOf(CompanionPromptVariant(id = id, name = "Prompt 1", text = prompt, isDefault = true))
        }

        fun defaultPrompt(variants: List<CompanionPromptVariant>): String {
            return variants.firstOrNull { it.isDefault }?.text
                ?: variants.firstOrNull()?.text
                ?: ""
        }

        fun nextPromptName(variants: List<CompanionPromptVariant>): String {
            var max = 0
            for (v in variants) {
                val match = Regex("^Prompt (\\d+)$").find(v.name)
                if (match != null) {
                    val n = match.groupValues[1].toIntOrNull() ?: 0
                    if (n > max) max = n
                }
            }
            return "Prompt ${max + 1}"
        }
    }
}
