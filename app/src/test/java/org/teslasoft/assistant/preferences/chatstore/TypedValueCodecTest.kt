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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedValueCodecTest {

    private val everyType: Map<String, Any> = linkedMapOf(
        "text" to "hello 😀 \"quoted\"",
        "flag" to true,
        "small" to Int.MIN_VALUE,
        "big" to Long.MAX_VALUE,
        "ratio" to 0.1f,
        "tags" to linkedSetOf("a", "b", "")
    )

    @Test
    fun mapRoundTripsEveryTypeExactly() {
        val decoded = TypedValueCodec.decodeMap(TypedValueCodec.encodeMap(everyType)!!)!!
        assertEquals(everyType, decoded)
        assertTrue(decoded["small"] is Int)
        assertTrue(decoded["big"] is Long)
        assertTrue(decoded["ratio"] is Float)
    }

    @Test
    fun singleValuesRoundTripWithTheirType() {
        for ((_, value) in everyType) {
            val decoded = TypedValueCodec.decodeValue(TypedValueCodec.encodeValue(value)!!)
            assertEquals(value, decoded)
            assertEquals(value.javaClass, decoded!!.javaClass)
        }
    }

    @Test
    fun emptyMapRoundTrips() {
        assertEquals(emptyMap<String, Any>(), TypedValueCodec.decodeMap(TypedValueCodec.encodeMap(emptyMap())!!))
    }

    @Test
    fun unsupportedValuesAreRefusedNotDropped() {
        assertNull(TypedValueCodec.encodeMap(mapOf("ok" to "x", "bad" to 1.5)))
        assertNull(TypedValueCodec.encodeMap(mapOf("nul" to null)))
        assertNull(TypedValueCodec.encodeValue(setOf("a", 1)))
    }

    @Test
    fun malformedJsonDecodesToNull() {
        assertNull(TypedValueCodec.decodeMap("[]"))
        assertNull(TypedValueCodec.decodeMap("""{"a":{"t":"s"}}"""))
        assertNull(TypedValueCodec.decodeMap("""{"a":{"t":"b","v":"true"}}"""))
        assertNull(TypedValueCodec.decodeMap("""{"a":{"t":"zz","v":1}}"""))
        assertNull(TypedValueCodec.decodeValue("not json {"))
    }
}
