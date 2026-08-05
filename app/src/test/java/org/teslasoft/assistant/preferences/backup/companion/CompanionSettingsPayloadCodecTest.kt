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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The settings snapshot must survive the journal byte-for-type: a rollback
 * writes back EXACTLY what was captured, so every SharedPreferences value
 * type has to round-trip unchanged (crash-recovery correctness).
 */
class CompanionSettingsPayloadCodecTest {

    @Test
    fun everyPreferenceValueTypeRoundTrips() {
        val payload = CompanionSettingsPayload(
            personas = linkedMapOf(
                "p-1_label" to "Aria",
                "p-1_autoload_last_lorebooks" to "false"
            ),
            activationPrompts = linkedMapOf(
                "flag" to true,
                "count" to 42,
                "big" to 9_000_000_000L,
                "ratio" to 1.5f,
                "names" to setOf("a", "b"),
                "absent" to null
            ),
            systemPrompts = linkedMapOf(
                "list" to """[{"id":"sp-1","title":"T","body":"B"}]""",
                "selected_id" to "sp-1"
            ),
            systemMessage = "Be helpful."
        )

        val restored = CompanionSettingsPayloadCodec.fromJson(
            CompanionSettingsPayloadCodec.toJson(payload)
        )

        assertEquals(payload.personas, restored.personas)
        assertEquals(payload.activationPrompts, restored.activationPrompts)
        assertEquals(payload.systemPrompts, restored.systemPrompts)
        assertEquals(payload.systemMessage, restored.systemMessage)
        // Types, not just string renderings:
        assertEquals(true, restored.activationPrompts["flag"])
        assertEquals(42, restored.activationPrompts["count"])
        assertEquals(9_000_000_000L, restored.activationPrompts["big"])
        assertEquals(1.5f, restored.activationPrompts["ratio"])
        assertEquals(setOf("a", "b"), restored.activationPrompts["names"])
        assertEquals(null, restored.activationPrompts["absent"])
    }

    @Test
    fun emptyPayloadRoundTrips() {
        val payload = CompanionSettingsPayload(emptyMap(), emptyMap(), emptyMap(), "")
        assertEquals(
            payload,
            CompanionSettingsPayloadCodec.fromJson(CompanionSettingsPayloadCodec.toJson(payload))
        )
    }
}
