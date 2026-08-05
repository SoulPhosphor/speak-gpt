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

package org.teslasoft.assistant.preferences.backup.companion

import org.json.JSONArray
import org.json.JSONObject

/**
 * The complete app-settings state a companion/roleplay restore replaces
 * (companion-roleplay-backup-plan.md §6.3 step 3): the full key maps of the
 * `personas`, `activation_prompts` and `system_prompts` preference files,
 * plus the global system message the library mirrors into. One payload is
 * captured from the device before the restore writes anything (the rollback
 * snapshot); a second is built from the backup (the values to apply). Both
 * are persisted verbatim in the restore journal so an interruption — process
 * death included — can finish or undo the settings step at next startup.
 */
data class CompanionSettingsPayload(
    val personas: Map<String, Any?>,
    val activationPrompts: Map<String, Any?>,
    val systemPrompts: Map<String, Any?>,
    val systemMessage: String
)

/**
 * Type-preserving JSON round-trip for [CompanionSettingsPayload]. Values keep
 * their SharedPreferences types (String/Boolean/Int/Long/Float/Set&lt;String&gt;)
 * so a rollback writes back EXACTLY what was captured — never a stringified
 * copy of it. Pure org.json; unit-tested on the JVM.
 */
object CompanionSettingsPayloadCodec {

    private const val KEY_PERSONAS = "personas"
    private const val KEY_ACTIVATION = "activation_prompts"
    private const val KEY_SYSTEM_PROMPTS = "system_prompts"
    private const val KEY_SYSTEM_MESSAGE = "system_message"

    fun toJson(payload: CompanionSettingsPayload): String {
        val root = JSONObject()
        root.put(KEY_PERSONAS, mapToJson(payload.personas))
        root.put(KEY_ACTIVATION, mapToJson(payload.activationPrompts))
        root.put(KEY_SYSTEM_PROMPTS, mapToJson(payload.systemPrompts))
        root.put(KEY_SYSTEM_MESSAGE, payload.systemMessage)
        return root.toString()
    }

    /** Throws on malformed input — journal payloads are written by this codec
     *  alone, so a parse failure is a real defect, never user data. */
    fun fromJson(json: String): CompanionSettingsPayload {
        val root = JSONObject(json)
        return CompanionSettingsPayload(
            personas = jsonToMap(root.getJSONObject(KEY_PERSONAS)),
            activationPrompts = jsonToMap(root.getJSONObject(KEY_ACTIVATION)),
            systemPrompts = jsonToMap(root.getJSONObject(KEY_SYSTEM_PROMPTS)),
            systemMessage = root.getString(KEY_SYSTEM_MESSAGE)
        )
    }

    private fun mapToJson(map: Map<String, Any?>): JSONObject {
        val out = JSONObject()
        for ((key, value) in map) {
            val typed = JSONObject()
            when (value) {
                null -> {
                    typed.put("t", "null")
                }
                is String -> {
                    typed.put("t", "s"); typed.put("v", value)
                }
                is Boolean -> {
                    typed.put("t", "b"); typed.put("v", value)
                }
                is Int -> {
                    typed.put("t", "i"); typed.put("v", value)
                }
                is Long -> {
                    typed.put("t", "l"); typed.put("v", value)
                }
                is Float -> {
                    typed.put("t", "f"); typed.put("v", value.toDouble())
                }
                is Set<*> -> {
                    typed.put("t", "ss")
                    typed.put("v", JSONArray(value.map { it.toString() }))
                }
                else -> throw IllegalArgumentException(
                    "unsupported preference value type for $key"
                )
            }
            out.put(key, typed)
        }
        return out
    }

    private fun jsonToMap(json: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (key in json.keys()) {
            val typed = json.getJSONObject(key)
            out[key] = when (typed.getString("t")) {
                "null" -> null
                "s" -> typed.getString("v")
                "b" -> typed.getBoolean("v")
                "i" -> typed.getInt("v")
                "l" -> typed.getLong("v")
                "f" -> typed.getDouble("v").toFloat()
                "ss" -> {
                    val array = typed.getJSONArray("v")
                    val set = LinkedHashSet<String>()
                    for (i in 0 until array.length()) set.add(array.getString(i))
                    set
                }
                else -> throw IllegalArgumentException("unknown preference value tag")
            }
        }
        return out
    }
}
