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

package org.teslasoft.assistant.preferences.memory.archivist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchivistResponseParserTest {

    @Test
    fun parsesWellFormedResponse() {
        // Unsupported legacy fields (title, importance, provenance, and the
        // free-text "type") are ignored: DraftMemory has no such surface, and the
        // Type suggestion is read only from the new "type_id" field.
        val parsed = ArchivistResponseParser.parse(
            """
            {"memories":[{"title":"Coffee order","content":"Prefers oat-milk lattes.","scope":"real_life","type":"preference","type_id":"mtype-preference","importance":2,"tags":["Food"],"provenance":"stated"}],
             "model_rules":[{"text":"Do not end responses with a question."}]}
            """.trimIndent()
        )
        assertEquals(1, parsed.memories.size)
        val m = parsed.memories[0]
        assertEquals("Prefers oat-milk lattes.", m.content)
        assertEquals("real_life", m.scope)
        assertEquals("mtype-preference", m.typeIdSuggestion)
        assertEquals(listOf("Food"), m.tags)
        assertNull(m.targetName)
        assertEquals(1, parsed.rules.size)
        assertEquals(0, parsed.dropped)
    }

    @Test
    fun unwrapsMarkdownFenceAndProse() {
        val parsed = ArchivistResponseParser.parse(
            "Here you go:\n```json\n{\"memories\":[],\"model_rules\":[]}\n```\nDone."
        )
        assertTrue(parsed.memories.isEmpty())
        assertTrue(parsed.rules.isEmpty())
    }

    @Test
    fun dropsUnknownScope_butNeverDropsOnTypeSuggestion() {
        // Only an unknown SCOPE (or empty content) drops a proposal. The Type
        // suggestion never drops a memory: a known id, an unknown id, and an
        // absent id all survive — the id is validated to No Type only at filing.
        val parsed = ArchivistResponseParser.parse(
            """
            {"memories":[
              {"content":"b","scope":"secret","type_id":"mtype-fact"},
              {"content":"kept-known","scope":"real_life","type_id":"mtype-fact"},
              {"content":"kept-unknown-id","scope":"real_life","type_id":"totally-made-up"},
              {"content":"","scope":"real_life","type_id":"mtype-fact"},
              {"content":"kept-no-type","scope":"global"}
            ]}
            """.trimIndent()
        )
        // Kept: known, unknown-id, no-type. Dropped: unknown scope, empty content.
        assertEquals(3, parsed.memories.size)
        assertEquals(setOf("kept-known", "kept-unknown-id", "kept-no-type"),
            parsed.memories.map { it.content }.toSet())
        assertEquals("mtype-fact", parsed.memories.first { it.content == "kept-known" }.typeIdSuggestion)
        // An unknown id is carried as-is (filing maps it to No Type); an absent id
        // is null (No Type). Neither drops the memory.
        assertEquals("totally-made-up", parsed.memories.first { it.content == "kept-unknown-id" }.typeIdSuggestion)
        assertNull(parsed.memories.first { it.content == "kept-no-type" }.typeIdSuggestion)
        assertEquals(2, parsed.dropped)
    }

    @Test
    fun protectionFieldsAreIgnoredNotStored() {
        // Retired July 8 2026: any handling field the model emits must vanish.
        val parsed = ArchivistResponseParser.parse(
            """
            {"memories":[{"title":"t","content":"c","scope":"real_life","type":"fact",
              "protection":{"handling":"gently"},"never_assume":["x"]}]}
            """.trimIndent()
        )
        assertEquals(1, parsed.memories.size)
        // DraftMemory simply has no protection surface — nothing to assert
        // beyond the row surviving without one.
    }

    @Test
    fun importanceIsNeverParsed() {
        // The Memory Assistant does not assign importance (§7.2). Whatever the
        // model emits for importance is ignored; DraftMemory has no importance
        // field, and every proposal starts neutral at the filing layer.
        val parsed = ArchivistResponseParser.parse(
            """
            {"memories":[
              {"content":"c","scope":"real_life","type":"fact","importance":9},
              {"content":"d","scope":"real_life","type":"fact"}
            ]}
            """.trimIndent()
        )
        // Both survive regardless of any importance value — none was consulted.
        assertEquals(2, parsed.memories.size)
        assertEquals(0, parsed.dropped)
    }

    @Test
    fun legacyProvenanceAndTypeFieldsAreIgnoredSafely() {
        // A response that still uses the old "type"/"provenance" fields is parsed
        // without error: those fields are simply not read, and no "type_id" means
        // No Type. The memory is never dropped for carrying them.
        val parsed = ArchivistResponseParser.parse(
            """{"memories":[{"title":"a","content":"c","scope":"real_life","type":"fact","provenance":"stated"}]}"""
        )
        assertEquals(1, parsed.memories.size)
        assertNull(parsed.memories[0].typeIdSuggestion)
    }

    @Test
    fun memoryFloodIsBoundedAndCounted() {
        val rows = (1..50).joinToString(",") {
            """{"title":"t$it","content":"c","scope":"real_life","type":"fact"}"""
        }
        val parsed = ArchivistResponseParser.parse("""{"memories":[$rows]}""")
        assertEquals(ArchivistResponseParser.MAX_MEMORIES_PER_CONVERSATION, parsed.memories.size)
        assertEquals(50 - ArchivistResponseParser.MAX_MEMORIES_PER_CONVERSATION, parsed.dropped)
    }

    @Test
    fun targetNameAndScopeAcceptedForRoleplay() {
        val parsed = ArchivistResponseParser.parse(
            """
            {"memories":[{"title":"The sword","content":"Found in the ruins.","scope":"campaign","type":"lore","target":"Shadowfell"}]}
            """.trimIndent()
        )
        assertEquals("campaign", parsed.memories[0].scope)
        assertEquals("Shadowfell", parsed.memories[0].targetName)
    }

    @Test(expected = Exception::class)
    fun noJsonObjectThrows() {
        ArchivistResponseParser.parse("I could not analyze this conversation.")
    }

    @Test
    fun blankRuleDroppedAndRuleFloodBounded() {
        val sevenRules = (1..7).joinToString(",") { """{"text":"rule $it"}""" }
        val parsed = ArchivistResponseParser.parse(
            """{"memories":[],"model_rules":[$sevenRules,{"text":" "}]}"""
        )
        assertEquals(ArchivistResponseParser.MAX_RULES_PER_CONVERSATION, parsed.rules.size)
        assertEquals(3, parsed.dropped) // 2 over the cap + 1 blank
    }

    /* ---------------- Lorebook Memories analysis (Step 1.7) ---------------- */

    @Test
    fun parsesWellFormedLoreEntries() {
        val parsed = ArchivistResponseParser.parseLore(
            """
            {"entries":[
              {"content":"Eldoria is a sunken kingdom beneath the northern sea.","triggers":["Eldoria","sunken kingdom"]}
            ]}
            """.trimIndent()
        )
        assertEquals(1, parsed.entries.size)
        assertEquals("Eldoria is a sunken kingdom beneath the northern sea.", parsed.entries[0].content)
        assertEquals(listOf("Eldoria", "sunken kingdom"), parsed.entries[0].triggers)
        assertEquals(0, parsed.dropped)
    }

    @Test
    fun loreEntryWithoutTriggersIsDropped() {
        // A lore book entry with no keyword could never fire — never coerce it
        // into a keywordless entry; drop and count it.
        val parsed = ArchivistResponseParser.parseLore(
            """
            {"entries":[
              {"content":"has no triggers","triggers":[]},
              {"content":"empty triggers omitted","triggers":["  "]},
              {"content":"kept","triggers":["Keyword"]}
            ]}
            """.trimIndent()
        )
        assertEquals(1, parsed.entries.size)
        assertEquals("kept", parsed.entries[0].content)
        assertEquals(2, parsed.dropped)
    }

    @Test
    fun loreEntryWithoutContentIsDropped() {
        val parsed = ArchivistResponseParser.parseLore(
            """{"entries":[{"content":"  ","triggers":["x"]},{"content":"ok","triggers":["y"]}]}"""
        )
        assertEquals(1, parsed.entries.size)
        assertEquals(1, parsed.dropped)
    }

    @Test
    fun loreTriggersDedupedCaseInsensitively() {
        val parsed = ArchivistResponseParser.parseLore(
            """{"entries":[{"content":"c","triggers":["Fog","fog","FOG","Mist"]}]}"""
        )
        assertEquals(listOf("Fog", "Mist"), parsed.entries[0].triggers)
    }

    @Test
    fun loreEntryFloodIsBoundedAndCounted() {
        val rows = (1..50).joinToString(",") {
            """{"content":"c$it","triggers":["t$it"]}"""
        }
        val parsed = ArchivistResponseParser.parseLore("""{"entries":[$rows]}""")
        assertEquals(ArchivistResponseParser.MAX_LORE_ENTRIES_PER_CONVERSATION, parsed.entries.size)
        assertEquals(50 - ArchivistResponseParser.MAX_LORE_ENTRIES_PER_CONVERSATION, parsed.dropped)
    }

    @Test
    fun loreUnwrapsMarkdownFence() {
        val parsed = ArchivistResponseParser.parseLore(
            "```json\n{\"entries\":[]}\n```"
        )
        assertTrue(parsed.entries.isEmpty())
        assertEquals(0, parsed.dropped)
    }

    @Test(expected = Exception::class)
    fun loreNoJsonObjectThrows() {
        ArchivistResponseParser.parseLore("no json here")
    }
}
