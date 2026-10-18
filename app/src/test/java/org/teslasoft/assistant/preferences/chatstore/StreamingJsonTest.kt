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

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingJsonTest {

    private companion object {
        val DUPLICATE_NAME_SAMPLES = setOf("""[{"a":null,"a":2}]""", """[{"n":{"x":1,"x":2}}]""")
    }

    /** The app's own read of a stored list (ChatPreferences): null when it throws. */
    private fun gsonListSize(json: String): Int? = try {
        val type = TypeToken.getParameterized(ArrayList::class.java, HashMap::class.java).type
        val list: ArrayList<*>? = Gson().fromJson(json, type)
        list?.size ?: 0
    } catch (_: Exception) {
        null
    }

    private fun count(json: String) = StreamingJson.countObjectList(StringReader(json))

    private val samples = listOf(
        "", "   ", "null", "[]", " [ ] ",
        """[{"message":"hi","isBot":false}]""",
        """[{"a":1},{"b":"x"},{"c":[1,2,{"d":null}]}]""",
        """[{},null]""",
        """[{"a":null,"a":2}]""",
        """[{"a":1,"a":null}]""",
        """[{"a":1,"a":2}]""",
        """[{"n":{"x":1,"x":2}}]""",
        """[{a:1}]""",
        """[{'a':'b'}]""",
        """[{"a":1};{"b":2}]""",
        """[{"a":NaN}]""",
        """[{"a":1}] // trailing comment""",
        """[{"a":1}] x""",
        """[{"a":1}] []""",
        """null garbage""",
        """[{"a":1},]""",
        """[,{"a":1}]""",
        """[1]""", """["text"]""", """{}""", "\"text\"", "42",
        """[{"a":1}""", """[{"a":""", """[{"a":"unterminated""",
        """[{"a":1e999}]""", """[{"a":12345678901234567890123}]""",
        """[{"emoji":"😀","lone":"\uD83D"}]"""
    )

    @Test
    fun countAgreesWithTheAppsOwnReadForEverySample() {
        for (json in samples) {
            val expected = gsonListSize(json) ?: StreamingJson.INVALID
            assertEquals("sample: $json", expected, count(json))
        }
    }

    /**
     * Gson also reads a map written as an array of [key, value] pairs. The
     * app never stores messages that way, so the streaming check is stricter
     * here; callers then defer to the app's own read instead of deciding.
     */
    @Test
    fun pairArrayMapsAreRefusedAlthoughGsonReadsThem() {
        for (json in listOf("""[[]]""", """[[["k","v"]]]""")) {
            assertEquals(json, 1, gsonListSize(json))
            assertEquals(json, StreamingJson.INVALID, count(json))
        }
    }

    @Test
    fun truncatedListIsDamagedNotEmpty() {
        assertEquals(StreamingJson.INVALID, count("""[{"message":"hello"},{"mess"""))
    }

    @Test
    fun elementsComeBackInOrderWithNumberTextUnchanged() {
        val json = """[{"messageTime":1.7E12,"n":10},null,{"message":"<b>&</b>"}]"""
        val seen = ArrayList<String>()
        StreamingJson.forEachElement(StringReader(json)) { seen.add(it.toString()); true }
        assertEquals(
            listOf("""{"messageTime":1.7E12,"n":10}""", "null", """{"message":"<b>&</b>"}"""),
            seen
        )
    }

    @Test
    fun streamedElementsMatchTheTreeTextForEverySample() {
        for (json in samples) {
            if (count(json) == StreamingJson.INVALID) continue
            val tree = ArrayList<String>()
            StreamingJson.forEachElement(StringReader(json)) { tree.add(it.toString()); true }
            val streamed = ArrayList<String>()
            StreamingJson.forEachElementStreamed(StringReader(json)) { streamed.add(it.asString()); true }
            // Gson's tree keeps only the last of two same-named fields; the
            // streamed copy keeps the stored text as it is.
            if (json in DUPLICATE_NAME_SAMPLES) continue
            assertEquals("sample: $json", tree, streamed)
        }
    }

    @Test
    fun streamedElementsCanBeSkippedOrCopiedOnce() {
        val json = """[{"message":"one"},{"message":"two"},{"message":"three"}]"""
        val seen = ArrayList<String>()
        var index = 0
        StreamingJson.forEachElementStreamed(StringReader(json)) { element ->
            if (index++ != 1) seen.add(StringBuilder().also { element.copyTo(it) }.toString())
            true
        }
        assertEquals(listOf("""{"message":"one"}""", """{"message":"three"}"""), seen)
    }

    @Test
    fun collectFieldsTakesOnlyTopLevelPrimitives() {
        val json = """[{"message_id":"a","isBot":true,"x":{"message_id":"inner"}},null,""" +
            """{"isBot":"true","message_id":7,"message_id":"b"},{"message_id":{"o":1}}]"""
        val fields = StreamingJson.collectFields(StringReader(json), setOf("message_id", "isBot"))
        assertEquals(4, fields.size)
        assertEquals("a", fields[0]["message_id"]?.asString)
        assertEquals(true, fields[0]["isBot"]?.asBoolean)
        assertEquals(emptyMap<String, Any>(), fields[1])
        assertEquals("b", fields[2]["message_id"]?.asString)
        assertEquals("true", fields[2]["isBot"]?.asString)
        assertEquals(null, fields[3]["message_id"])
    }

    @Test
    fun forEachElementStopsWhenAsked() {
        var visits = 0
        StreamingJson.forEachElement(StringReader("""[{},{},{}]""")) { visits++; visits < 2 }
        assertEquals(2, visits)
    }

    @Test
    fun emptyDocumentsVisitNothing() {
        for (json in listOf("", "null", "[]")) {
            var visits = 0
            StreamingJson.forEachElement(StringReader(json)) { visits++; true }
            assertEquals(json, 0, visits)
        }
    }

    @Test
    fun aListElementIsOneReadableObjectOrNull() {
        for (json in listOf("{}", "{\"message\":\"hi\"}", "null", " { \"a\": null, \"a\": 1 } ")) {
            assertEquals(json, true, StreamingJson.isListElement(StringReader(json)))
        }
        for (json in listOf("", "1", "\"text\"", "[]", "{\"a\":1,\"a\":2}", "{} {}", "{", "{}/*x*/")) {
            assertEquals(json, false, StreamingJson.isListElement(StringReader(json)))
        }
    }
}
