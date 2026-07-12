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

package org.teslasoft.assistant.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preserved-artifact registry's pure guarantees: snapshot names/ids are
 * collision-proof (never timestamp-only — two conflicts in the same
 * millisecond must not overwrite each other), and a registry entry can
 * never smuggle payload content into the plaintext journal — entryJson is
 * the single writer shape and emits exactly the allowed metadata keys.
 */
class SnapshotRegistryTest {

    @Test fun uniqueSuffixNeverCollides_evenInBulkWithinOneMillisecond() {
        val seen = HashSet<String>()
        repeat(2000) {
            assertTrue("duplicate suffix generated", seen.add(SnapshotRegistry.uniqueSuffix()))
        }
    }

    @Test fun uniqueSuffixIsFilenameSafe() {
        repeat(50) {
            val s = SnapshotRegistry.uniqueSuffix()
            assertTrue(s.all { it.isLetterOrDigit() || it == '_' })
        }
    }

    @Test fun entryJsonEmitsOnlyAllowedMetadataKeys() {
        val obj = SnapshotRegistry.entryJson(
            id = "id1",
            snapshotName = "chat_abc_recovered_x1",
            sourceName = "chat_abc",
            chatId = "abc",
            reason = "outage_conflict",
            origin = SnapshotRegistry.ORIGIN_OUTAGE_RECONCILIATION,
            createdAt = 123L
        )
        val allowed = setOf("id", "snapshot", "source", "chat_id", "reason", "origin", "created", "status")
        for (key in obj.keys()) {
            assertTrue("unexpected registry key '$key'", key in allowed)
        }
        assertEquals("preserved", obj.getString("status"))
    }

    @Test fun payloadTextInReasonOrOriginIsNeutralized() {
        // A hostile/buggy caller passing message text as the reason must not
        // get it into the plaintext journal: tokens are clamped and stripped.
        val poison = "user said: my api key is sk-SECRET/12345 !"
        val obj = SnapshotRegistry.entryJson(
            id = "id2", snapshotName = "s", sourceName = "chat_x", chatId = null,
            reason = poison, origin = poison, createdAt = 1L
        )
        val reason = obj.getString("reason")
        val origin = obj.getString("origin")
        for (v in listOf(reason, origin)) {
            assertFalse(v.contains("sk-SECRET"))
            assertFalse(v.contains("/"))
            assertFalse(v.contains(" "))
            assertTrue(v.length <= 48)
        }
        // chat_id omitted entirely when unknown — no empty placeholder.
        assertFalse(obj.has("chat_id"))
    }

    @Test fun chatIdIsDerivedFromChatFileNamesOnly() {
        assertEquals("abc", SnapshotRegistry.chatIdFrom("chat_abc"))
        assertEquals("abc", SnapshotRegistry.chatIdFrom("settings.abc"))
        // The chat LIST is not a chat; no id may be invented for it.
        assertEquals(null, SnapshotRegistry.chatIdFrom("chat_list"))
        assertEquals(null, SnapshotRegistry.chatIdFrom("rename_journal"))
    }
}
