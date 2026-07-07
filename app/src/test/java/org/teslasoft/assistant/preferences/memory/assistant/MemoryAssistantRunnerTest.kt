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

package org.teslasoft.assistant.preferences.memory.assistant

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Memory Assistant's law layer (Phase 6): everything the model returns
 * passes through extractJsonArray + sanitize before anything touches the
 * store. These tests pin the safety behavior — the fiction wall, the type
 * whitelist, the suggestion cap — because a quiet regression here is exactly
 * the class of violation the owner-approved rules exist to prevent.
 */
class MemoryAssistantRunnerTest {

    private fun suggestion(
        title: String = "Sister named Kay",
        content: String = "The user has a sister named Kay.",
        type: String = "fact",
        scope: String = "real_life"
    ): String = """{"title":"$title","content":"$content","type":"$type","scope":"$scope","importance":3,"tags":["family"]}"""

    private val ordinaryScopes = listOf("global", "real_life", "companion")
    private val roleplayScopes = listOf("global", "world", "campaign", "rp_character")

    /* ------------------------------ parsing ------------------------------ */

    @Test
    fun parsesPlainArray() {
        val arr = MemoryAssistantRunner.extractJsonArray("[${suggestion()}]")
        assertNotNull(arr)
        assertEquals(1, arr!!.length())
    }

    @Test
    fun parsesFencedArrayWithChatter() {
        val reply = "Here are the memories:\n```json\n[${suggestion()}]\n```\nDone!"
        val arr = MemoryAssistantRunner.extractJsonArray(reply)
        assertNotNull(arr)
        assertEquals(1, arr!!.length())
    }

    @Test
    fun emptyArrayIsValid() {
        // "A run that finds nothing is a success."
        val arr = MemoryAssistantRunner.extractJsonArray("[]")
        assertNotNull(arr)
        assertEquals(0, arr!!.length())
    }

    @Test
    fun proseReplyIsUnusable() {
        assertNull(MemoryAssistantRunner.extractJsonArray("I could not find any memories worth keeping."))
    }

    /* ------------------------- the fiction wall ------------------------- */

    @Test
    fun realLifeScopeDroppedInRoleplay() {
        val arr = JSONArray("[${suggestion(scope = "real_life")},${suggestion(title = "The dragon", content = "A dragon guards the pass.", type = "lore", scope = "world")}]")
        val out = MemoryAssistantRunner.sanitize(arr, roleplayScopes, 0)
        assertEquals(1, out.size)
        assertEquals("world", out[0].scope)
    }

    @Test
    fun fictionScopeDroppedInOrdinaryConversation() {
        val arr = JSONArray("[${suggestion(scope = "campaign")},${suggestion(scope = "companion")}]")
        val out = MemoryAssistantRunner.sanitize(arr, ordinaryScopes, 0)
        assertEquals(1, out.size)
        assertEquals("companion", out[0].scope)
    }

    @Test
    fun globalOnlyParticipationBlocksEverythingElse() {
        val arr = JSONArray("[${suggestion(scope = "real_life")},${suggestion(scope = "companion")},${suggestion(scope = "global")}]")
        val out = MemoryAssistantRunner.sanitize(arr, listOf("global"), 0)
        assertEquals(1, out.size)
        assertEquals("global", out[0].scope)
    }

    /* ----------------------------- validation ----------------------------- */

    @Test
    fun unknownTypeAndBlankFieldsDropped() {
        val arr = JSONArray(
            "[${suggestion(type = "note")}," +
                "${suggestion(title = "")}," +
                """{"scope":"real_life","type":"fact"},""" +
                suggestion() + "]"
        )
        val out = MemoryAssistantRunner.sanitize(arr, ordinaryScopes, 0)
        assertEquals(1, out.size)
        assertEquals("fact", out[0].type)
    }

    @Test
    fun nonObjectEntriesIgnored() {
        val arr = JSONArray("""["just a string", 42, ${suggestion()}]""")
        val out = MemoryAssistantRunner.sanitize(arr, ordinaryScopes, 0)
        assertEquals(1, out.size)
    }

    @Test
    fun importanceClampedAndTagsCapped() {
        val arr = JSONArray(
            """[{"title":"T","content":"C","type":"fact","scope":"global","importance":99,"tags":["a","b","c","d","e","f","g"]}]"""
        )
        val out = MemoryAssistantRunner.sanitize(arr, ordinaryScopes, 0)
        assertEquals(1, out.size)
        assertEquals(5, out[0].importance)
        assertEquals(5, out[0].tags.size)
    }

    @Test
    fun missingImportanceDefaultsToNotable() {
        val arr = JSONArray("""[{"title":"T","content":"C","type":"fact","scope":"global"}]""")
        val out = MemoryAssistantRunner.sanitize(arr, ordinaryScopes, 0)
        assertEquals(3, out[0].importance)
        assertTrue(out[0].tags.isEmpty())
    }

    /* ------------------------------- the cap ------------------------------- */

    @Test
    fun capEnforcedInCode() {
        val many = (1..10).joinToString(",") { suggestion(title = "Memory $it") }
        val out = MemoryAssistantRunner.sanitize(JSONArray("[$many]"), ordinaryScopes, 3)
        assertEquals(3, out.size)
    }

    @Test
    fun capZeroMeansOff() {
        val many = (1..10).joinToString(",") { suggestion(title = "Memory $it") }
        val out = MemoryAssistantRunner.sanitize(JSONArray("[$many]"), ordinaryScopes, 0)
        assertEquals(10, out.size)
    }
}
