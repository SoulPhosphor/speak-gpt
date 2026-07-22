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

package org.teslasoft.assistant.preferences.backup.portable

import org.junit.Assert.assertEquals
import org.junit.Test
import org.teslasoft.assistant.util.Hash

/**
 * Compatibility contract (July 22 2026 on-device failure): the recovery
 * serializer must locate each chat's history under EXACTLY the id the app
 * itself uses. ChatPreferences hashes `map["name"].toString()` everywhere —
 * so a missing name hashes the literal string "null", and a blank or
 * whitespace name hashes that exact string. The serializer must mirror this,
 * not "correct" it: an entry the app displays every day must never turn into
 * "Your chats could not be read".
 */
class ChatLogicalSerializerCompatTest {

    @Test
    fun storedNameMapsExactlyLikeTheApp() {
        // Present name: unchanged.
        assertEquals("My Chat", ChatLogicalSerializer.storedNameForId("My Chat"))
        // Blank/whitespace names are legitimate stored values, kept verbatim.
        assertEquals("", ChatLogicalSerializer.storedNameForId(""))
        assertEquals("  ", ChatLogicalSerializer.storedNameForId("  "))
        // A missing name behaves like the app's map["name"].toString().
        assertEquals("null", ChatLogicalSerializer.storedNameForId(null))
    }

    @Test
    fun derivedIdsMatchTheAppsHashForEveryShape() {
        val appMapping = { stored: String? -> Hash.hash(stored.toString()) }
        for (stored in listOf("My Chat", "", "  ", null)) {
            assertEquals(
                appMapping(stored),
                Hash.hash(ChatLogicalSerializer.storedNameForId(stored))
            )
        }
    }
}
