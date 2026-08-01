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
        val parsed = ArchivistResponseParser.parse(
            """
            {"memories":[{"title":"Coffee order","content":"Prefers oat-milk lattes.","scope":"real_life","type":"preference","importance":2,"tags":["Food"],"provenance":"stated"}],
             "model_rules":[{"text":"Do not end responses with a question."}]}
            """.trimIndent()
        )
        assertEquals(1, parsed.memories.size)
        val m = parsed.memories[0]
        assertEquals("Coffee order", m.title)
        assertEquals("real_life", m.scope)
        assertEquals("preference", m.kind)
        assertEquals(2, m.importance)
        assertEquals(listOf("Food"), m.tags)
        assertTrue(m.stated)
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
    fun dropsUnknownScopeAndType_neverCoerces() {
        val parsed = ArchivistResponseParser.parse(
            """
            {"memories":[
              {"title":"a","content":"b","scope":"secret","type":"fact"},
              {"title":"a","content":"b","scope":"real_life","type":"vibe"},
              {"title":"","content":"b","scope":"real_life","type":"fact"},
              {"title":"ok","content":"kept","scope":"global","type":"instruction"}
            ]}
            """.trimIndent()
        )
        assertEquals(1, parsed.memories.size)
        assertEquals("ok", parsed.memories[0].title)
        assertEquals(3, parsed.dropped)
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
    fun importanceClampedAndDefaulted() {
        val parsed = ArchivistResponseParser.parse(
            """
            {"memories":[
              {"title":"a","content":"c","scope":"real_life","type":"fact","importance":9},
              {"title":"b","content":"c","scope":"real_life","type":"fact","importance":0},
              {"title":"d","content":"c","scope":"real_life","type":"fact"}
            ]}
            """.trimIndent()
        )
        assertEquals(5, parsed.memories[0].importance)
        assertEquals(1, parsed.memories[1].importance)
        assertEquals(3, parsed.memories[2].importance)
    }

    @Test
    fun provenanceDefaultsToInferred() {
        val parsed = ArchivistResponseParser.parse(
            """{"memories":[{"title":"a","content":"c","scope":"real_life","type":"fact"}]}"""
        )
        assertFalse(parsed.memories[0].stated)
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

    @Test
    fun cardPlacementSuggestionParsed() {
        val parsed = ArchivistResponseParser.parse(
            """
            {"memories":[{"title":"Moonblade","content":"Won from the tomb.","scope":"rp_character","type":"lore",
              "card":"Kaelen","card_section":"INVENTORY"}]}
            """.trimIndent()
        )
        assertEquals("Kaelen", parsed.memories[0].cardName)
        // Section keys normalize to lowercase; validation against the card's
        // real section list happens in the runner.
        assertEquals("inventory", parsed.memories[0].cardSection)
    }

    @Test
    fun missingCardFieldsStayNull() {
        val parsed = ArchivistResponseParser.parse(
            """{"memories":[{"title":"a","content":"c","scope":"world","type":"lore"}]}"""
        )
        assertNull(parsed.memories[0].cardName)
        assertNull(parsed.memories[0].cardSection)
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
