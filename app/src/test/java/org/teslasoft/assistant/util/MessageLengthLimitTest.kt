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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageLengthLimitTest {

    @Test
    fun allowsTextUpToTheLimit() {
        assertFalse(MessageLengthLimit.exceeds(""))
        assertFalse(MessageLengthLimit.exceeds("a".repeat(MessageLengthLimit.MAX_CHARACTERS)))
    }

    @Test
    fun refusesTextOverTheLimit() {
        assertTrue(MessageLengthLimit.exceeds("a".repeat(MessageLengthLimit.MAX_CHARACTERS + 1)))
    }

    @Test
    fun countsAnEmojiAsOneCharacter() {
        val emoji = "😀"
        assertEquals(1, MessageLengthLimit.characterCount(emoji))
        // Twice the limit in UTF-16 units, but exactly the limit in characters.
        assertFalse(MessageLengthLimit.exceeds(emoji.repeat(MessageLengthLimit.MAX_CHARACTERS)))
        assertTrue(MessageLengthLimit.exceeds(emoji.repeat(MessageLengthLimit.MAX_CHARACTERS + 1)))
    }
}
