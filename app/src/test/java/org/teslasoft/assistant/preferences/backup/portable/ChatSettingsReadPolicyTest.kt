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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner-required four cases (July 22 2026): a LOCKED per-chat settings
 * file presents as an inert empty map without throwing, so the lock flag —
 * not the exception path — must drive the failure, and an empty map from an
 * UNLOCKED file must stay legal.
 */
class ChatSettingsReadPolicyTest {

    @Test
    fun readableSettingsWithValues() {
        val entries = mapOf<String, Any?>("model" to "glm-4", "temp" to 0.7f)
        val d = ChatSettingsReadPolicy.decide(locked = false, entriesOrNull = entries)
        assertTrue(d is ChatSettingsReadPolicy.Decision.Readable)
        assertEquals(entries, (d as ChatSettingsReadPolicy.Decision.Readable).entries)
    }

    @Test
    fun readableEmptySettingsAreHonestData() {
        val d = ChatSettingsReadPolicy.decide(locked = false, entriesOrNull = emptyMap())
        assertTrue(d is ChatSettingsReadPolicy.Decision.Readable)
        assertTrue((d as ChatSettingsReadPolicy.Decision.Readable).entries.isEmpty())
    }

    @Test
    fun lockedSettingsAreUnavailableEvenThoughTheMapReadsEmpty() {
        // The Round-4 masquerade: a locked file returns an EMPTY map without
        // throwing. The lock flag must win over the innocent-looking map.
        val d = ChatSettingsReadPolicy.decide(locked = true, entriesOrNull = emptyMap())
        assertTrue(d is ChatSettingsReadPolicy.Decision.Unavailable)
    }

    @Test
    fun readExceptionIsUnavailableNeverEmptySubstitution() {
        val d = ChatSettingsReadPolicy.decide(locked = false, entriesOrNull = null)
        assertTrue(d is ChatSettingsReadPolicy.Decision.Unavailable)
    }
}
