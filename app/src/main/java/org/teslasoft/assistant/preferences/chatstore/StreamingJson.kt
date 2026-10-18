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
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.EOFException
import java.io.Reader
import java.io.Writer
import java.nio.CharBuffer

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
     * True when [json] is one list element the app's read accepts: an object
     * (the same rules as each element in [countObjectList]) or `null`,
     * followed by nothing but whitespace.
     */
    fun isListElement(json: Reader): Boolean = try {
        val reader = lenientReader(json)
        val readable = when (reader.peek()) {
            JsonToken.NULL -> { reader.nextNull(); true }
            JsonToken.BEGIN_OBJECT -> objectReadable(reader)
            else -> false
        }
        reader.strictness = Strictness.LEGACY_STRICT
        readable && reader.peek() == JsonToken.END_DOCUMENT
    } catch (_: Exception) {
        false
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

    /**
     * For each element of a list that [countObjectList] accepted, the
     * top-level primitive values of the requested [names] (the last
     * occurrence wins, as in the app's own read). A null element, or a name
     * whose value is not a primitive, contributes nothing. Values of other
     * names are skipped without being built.
     */
    fun collectFields(json: Reader, names: Set<String>): List<Map<String, JsonPrimitive>> =
        fieldsOneAtATime(json, names).asSequence().toList()

    /**
     * The same fields as [collectFields], one element at a time, so a caller
     * walking the list alongside [forEachElementStreamed] holds only the
     * current element's fields.
     */
    fun fieldsOneAtATime(json: Reader, names: Set<String>): Iterator<Map<String, JsonPrimitive>> = iterator {
        val reader = lenientReader(json)
        val first = try { reader.peek() } catch (_: EOFException) { return@iterator }
        if (first != JsonToken.BEGIN_ARRAY) return@iterator
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                yield(emptyMap())
                continue
            }
            val fields = HashMap<String, JsonPrimitive>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name !in names) {
                    reader.skipValue()
                    continue
                }
                when (reader.peek()) {
                    JsonToken.STRING -> fields[name] = JsonPrimitive(reader.nextString())
                    JsonToken.BOOLEAN -> fields[name] = JsonPrimitive(reader.nextBoolean())
                    else -> {
                        fields.remove(name)
                        reader.skipValue()
                    }
                }
            }
            reader.endObject()
            yield(fields)
        }
    }

    /**
     * Visit each element of a list that [countObjectList] accepted without
     * building it: [action] gets an [Element] whose JSON is copied straight
     * from the stored text token by token when asked. An element [action]
     * does not read is skipped. The JSON written is the same text
     * `JsonElement.toString()` would give, so no copy of the element as a
     * tree is ever made; only one string value at a time is held.
     */
    fun forEachElementStreamed(json: Reader, action: (Element) -> Boolean) {
        val reader = lenientReader(json)
        val first = try { reader.peek() } catch (_: EOFException) { return }
        if (first != JsonToken.BEGIN_ARRAY) return
        reader.beginArray()
        while (reader.hasNext()) {
            val element = Element(reader)
            val keepGoing = action(element)
            element.finish()
            if (!keepGoing) return
        }
    }

    /** One list element, readable once, during the visit that produced it. */
    class Element internal constructor(private val reader: JsonReader) {
        private var consumed = false
        private var text: String? = null

        /** The element's JSON as one string (kept, so it may be read again). */
        fun asString(): String =
            text ?: StringBuilder().also { copyTo(it) }.toString().also { text = it }

        /** Write the element's JSON to [out]. */
        fun copyTo(out: Appendable) {
            text?.let {
                out.append(it)
                return
            }
            check(!consumed) { "element already read" }
            consumed = true
            val writer = JsonWriter(AppendableWriter(out)).apply { strictness = Strictness.LENIENT }
            copyValue(reader, writer)
            writer.flush()
        }

        internal fun finish() {
            if (!consumed) {
                consumed = true
                reader.skipValue()
            }
        }
    }

    private fun copyValue(reader: JsonReader, writer: JsonWriter) {
        var depth = 0
        do {
            when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> { reader.beginArray(); writer.beginArray(); depth++ }
                JsonToken.END_ARRAY -> { reader.endArray(); writer.endArray(); depth-- }
                JsonToken.BEGIN_OBJECT -> { reader.beginObject(); writer.beginObject(); depth++ }
                JsonToken.END_OBJECT -> { reader.endObject(); writer.endObject(); depth-- }
                JsonToken.NAME -> writer.name(reader.nextName())
                JsonToken.STRING -> writer.value(reader.nextString())
                // The number's own text, as JsonElement.toString() keeps it.
                JsonToken.NUMBER -> writer.jsonValue(reader.nextString())
                JsonToken.BOOLEAN -> writer.value(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull(); writer.nullValue() }
                JsonToken.END_DOCUMENT, null -> throw IllegalStateException("truncated element")
            }
        } while (depth > 0)
    }

    private class AppendableWriter(private val out: Appendable) : Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) {
            out.append(CharBuffer.wrap(cbuf, off, len))
        }

        override fun write(str: String, off: Int, len: Int) {
            out.append(str, off, off + len)
        }

        override fun flush() = Unit
        override fun close() = Unit
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
