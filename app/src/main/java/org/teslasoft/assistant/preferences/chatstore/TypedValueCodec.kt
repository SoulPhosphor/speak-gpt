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

package org.teslasoft.assistant.preferences.chatstore

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * Preference values (the other keys of a chat's file and of the chat list)
 * as JSON that keeps each value's exact type, so a value written back is the
 * same type it was read as. Covers every type SharedPreferences can hold.
 *
 * One value is `{"t":<type>,"v":<value>}` with type s, b, i, l, f or ss
 * (string, boolean, int, long, float, string set). A float that is not a
 * finite number is written as the string "NaN", "Infinity" or "-Infinity",
 * since JSON has no number for it. A map is a JSON object of name to value.
 */
internal object TypedValueCodec {

    /** JSON for [value], or null when it is not a SharedPreferences type. */
    fun encodeValue(value: Any?): String? = valueElement(value)?.toString()

    /** The value [json] holds, or null when it is not a valid encoded value. */
    fun decodeValue(json: String): Any? = try {
        decodeElement(JsonParser.parseString(json))
    } catch (_: Exception) {
        null
    }

    /**
     * JSON for every entry of [values], or null if any value is not a
     * SharedPreferences type (nothing is silently left out).
     */
    fun encodeMap(values: Map<String, Any?>): String? {
        val out = JsonObject()
        for ((key, value) in values) {
            out.add(key, valueElement(value) ?: return null)
        }
        return out.toString()
    }

    /** The map [json] holds, or null when any part of it is not valid. */
    fun decodeMap(json: String): Map<String, Any>? {
        val root = try {
            JsonParser.parseString(json)
        } catch (_: Exception) {
            return null
        }
        if (!root.isJsonObject) return null
        val out = LinkedHashMap<String, Any>()
        for ((key, element) in root.asJsonObject.entrySet()) {
            out[key] = decodeElement(element) ?: return null
        }
        return out
    }

    private val NON_FINITE_FLOATS = mapOf(
        "NaN" to Float.NaN,
        "Infinity" to Float.POSITIVE_INFINITY,
        "-Infinity" to Float.NEGATIVE_INFINITY
    )

    private fun valueElement(value: Any?): JsonElement? {
        val (type, v) = when (value) {
            is String -> "s" to JsonPrimitive(value)
            is Boolean -> "b" to JsonPrimitive(value)
            is Int -> "i" to JsonPrimitive(value)
            is Long -> "l" to JsonPrimitive(value)
            is Float -> "f" to (if (value.isFinite()) JsonPrimitive(value) else JsonPrimitive(value.toString()))
            is Set<*> -> {
                val array = JsonArray()
                for (item in value) array.add(item as? String ?: return null)
                "ss" to array
            }
            else -> return null
        }
        return JsonObject().apply {
            addProperty("t", type)
            add("v", v)
        }
    }

    private fun decodeElement(element: JsonElement): Any? {
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        if (obj.size() != 2) return null
        val type = obj.get("t")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val v = obj.get("v") ?: return null
        return when (type) {
            "s" -> v.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            "b" -> v.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
            "i" -> v.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            "l" -> v.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
            "f" -> when {
                !v.isJsonPrimitive -> null
                v.asJsonPrimitive.isNumber -> v.asFloat
                v.asJsonPrimitive.isString -> NON_FINITE_FLOATS[v.asString]
                else -> null
            }
            "ss" -> if (!v.isJsonArray) null else {
                val set = LinkedHashSet<String>()
                for (item in v.asJsonArray) {
                    if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString) return null
                    set.add(item.asString)
                }
                set
            }
            else -> null
        }
    }
}
