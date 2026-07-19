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

package org.teslasoft.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HashTest {

    // Known SHA-256 test vectors (NIST / widely published).
    @Test
    fun byteHashOfEmptyInputMatchesTheKnownVector() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Hash.hash(ByteArray(0))
        )
    }

    @Test
    fun byteHashOfAbcMatchesTheKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hash.hash("abc".toByteArray())
        )
    }

    @Test
    fun byteHashIsDeterministic() {
        val bytes = "profile image bytes".toByteArray()
        assertEquals(Hash.hash(bytes), Hash.hash(bytes))
    }

    @Test
    fun byteHashDiffersForDifferentContent() {
        assertNotEquals(Hash.hash("a".toByteArray()), Hash.hash("b".toByteArray()))
    }

    @Test
    fun stringHashBehaviorIsUnchanged() {
        // Existing behavior used by persona labels and other identifiers -
        // must not be altered by adding the byte-safe overload.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hash.hash("abc")
        )
    }
}
