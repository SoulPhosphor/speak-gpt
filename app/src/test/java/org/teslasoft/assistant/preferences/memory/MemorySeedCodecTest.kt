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

package org.teslasoft.assistant.preferences.memory

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The seed/export codec is the contract between the store, the bundled starter
 * seed, backups, and the future cross-device sync — a silent asymmetry here
 * corrupts every one of them. These tests run against the real bundled
 * template (the same file shipped in assets), so a template edit that breaks
 * parsing fails CI instead of failing on the phone.
 */
class MemorySeedCodecTest {

    private fun templateJson(): String {
        // Gradle JVM tests run with the module directory as the working dir,
        // but be tolerant of a repo-root runner too.
        val candidates = listOf(
            File("src/main/assets/memory_seed_template.json"),
            File("app/src/main/assets/memory_seed_template.json")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("memory_seed_template.json asset not found from ${File(".").absolutePath}")
        return file.readText()
    }

    @Test
    fun parsesBundledTemplate() {
        val data = MemorySeedCodec.parse(templateJson())

        assertEquals("1.11.0", data.schemaVersion)
        assertNotNull(data.ownerProfile)
        assertEquals(1, data.companions.size)
        assertEquals(2, data.memories.size)
        assertEquals(8, data.modes.size)
        assertEquals(6, data.directives.size)
        assertEquals(1, data.entities.size)
        assertNotNull(data.archivistSettings)
        assertNotNull(data.retrievalPolicyJson)
        assertEquals("manual", data.archivistSettings!!.runTrigger)

        // The template's protected memory must carry its protection block —
        // the whole system's core invariant starts with the codec not losing it.
        val protected = data.memories.firstOrNull { it.protectionJson != null }
        assertNotNull("template should contain a protected memory", protected)
        val protection = JSONObject(protected!!.protectionJson!!)
        assertTrue(protection.getBoolean("is_protected"))
        assertTrue(protection.getJSONArray("handling").length() > 0)
    }

    @Test
    fun roundTripIsLossless() {
        val first = MemorySeedCodec.parse(templateJson())
        val serialized = MemorySeedCodec.serialize(first)
        val second = MemorySeedCodec.parse(serialized)

        // Data-class equality covers every field of every record, including the
        // raw-JSON passthrough columns (both sides normalized by org.json).
        assertEquals(first, second)
    }

    @Test
    fun exportEnvelopeCarriesChatsAndMeta() {
        val data = MemorySeedCodec.parse(templateJson())
        val chats = JSONArray().put(
            JSONObject().put("name", "Test chat").put("messages", JSONArray())
        )
        val out = JSONObject(MemorySeedCodec.serialize(data, appChats = chats, exportedAtIso = "2026-07-03T00:00:00Z"))

        assertEquals(1, out.getJSONArray("app_chats").length())
        val meta = out.getJSONObject("export_meta")
        assertEquals(MemorySeedCodec.EXPORT_FORMAT, meta.getString("format"))
        assertEquals("2026-07-03T00:00:00Z", meta.getString("exported_at"))
        // A strict schema reader must still see the standard top-level keys.
        assertTrue(out.has("companions"))
        assertTrue(out.has("memories"))
        assertTrue(out.has("retrieval_policy"))
    }
}
