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

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.EOFException
import java.io.Reader

/**
 * Element-at-a-time reading of the JSON lists the app stores today (a chat's
 * history and the chat list), so a reader never has to build the whole list.
 *
 * The app reads these lists with Gson as `ArrayList<HashMap>`. [countObjectList]
 * accepts what that read accepts, with one deliberate exception: Gson also
 * reads a map written as an array of [key, value] pairs, which the app never
 * stores, and this check refuses it. So a list called valid here is always one
 * the app can open; a list refused here must be referred to the app's own
 * read before anyone calls it damaged.
 */
internal object StreamingJson {

    /** Returned by [countObjectList] for a list the app's own read would reject. */
    const val INVALID = -1

    /**
     * Number of elements in the stored list, or [INVALID].
     *
     * Mirrors Gson's lenient `fromJson(json, ArrayList<HashMap>)`: an empty or
     * `null` document is an empty list; otherwise one array of objects or
     * nulls followed by nothing but whitespace. Gson's map read refuses a repeated name whose
     * earlier value was not null, so that is refused here too.
     */
    fun countObjectList(json: Reader): Int = try {
        val reader = lenientReader(json)
        val first = try {
            reader.peek()
        } catch (_: EOFException) {
            // An empty or whitespace-only document reads as no list at all.
            // A document cut off later is damaged and fails below instead.
            null
        }
        when (first) {
            null, JsonToken.NULL -> 0
            JsonToken.BEGIN_ARRAY -> countArray(reader)
            else -> INVALID
        }
    } catch (_: Exception) {
        INVALID
    }

    /**
     * Visit each element of a list that [countObjectList] accepted, oldest
     * first, holding one element at a time. [action] returns false to stop.
     * Behavior is undefined for a list that was not checked first.
     */
    fun forEachElement(json: Reader, action: (JsonElement) -> Boolean) {
        val reader = lenientReader(json)
        val first = try { reader.peek() } catch (_: EOFException) { return }
        if (first != JsonToken.BEGIN_ARRAY) return
        reader.beginArray()
        while (reader.hasNext()) {
            if (!action(JsonParser.parseReader(reader))) return
        }
    }

    private fun lenientReader(json: Reader): JsonReader =
        JsonReader(json).apply { strictness = Strictness.LENIENT }

    private fun countArray(reader: JsonReader): Int {
        var count = 0
        reader.beginArray()
        while (reader.hasNext()) {
            when (reader.peek()) {
                JsonToken.NULL -> reader.nextNull()
                JsonToken.BEGIN_OBJECT -> if (!objectReadable(reader)) return INVALID
                else -> return INVALID
            }
            count++
        }
        reader.endArray()
        // Gson checks what follows the list with its default strictness, not
        // leniently, so a trailing comment or value makes the read fail.
        reader.strictness = Strictness.LEGACY_STRICT
        return if (reader.peek() == JsonToken.END_DOCUMENT) count else INVALID
    }

    private fun objectReadable(reader: JsonReader): Boolean {
        val namesWithValues = HashSet<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (name in namesWithValues) return false
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull()
            } else {
                reader.skipValue()
                namesWithValues.add(name)
            }
        }
        reader.endObject()
        return true
    }
}
