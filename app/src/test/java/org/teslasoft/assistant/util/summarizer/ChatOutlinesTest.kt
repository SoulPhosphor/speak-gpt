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

package org.teslasoft.assistant.util.summarizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatOutlinesTest {

    private fun outline(name: String, promptId: String? = "p", custom: Boolean = false) =
        ChatOutline(text = "# $name", name = name, promptId = promptId, customName = custom)

    @Test
    fun jsonRoundTripKeepsOrderMarkdownAndNames() {
        val outlines = listOf(
            ChatOutline("# One\n- a", "Chat Outline", "builtin_chat_outline"),
            ChatOutline("## Two \"quoted\"", "Mine", null, customName = true)
        )
        assertEquals(outlines, ChatOutlines.fromJson(ChatOutlines.toJson(outlines)))
        assertEquals("", ChatOutlines.toJson(emptyList()))
        assertTrue(ChatOutlines.fromJson("").isEmpty())
        assertTrue(ChatOutlines.fromJson("not json").isEmpty())
    }

    @Test
    fun olderUnnamedOutlinesKeepTheirShownNames() {
        val older = """["first","second"]"""
        val loaded = ChatOutlines.fromJson(older) { index -> if (index == 0) "Chat Outline" else "Outline ${index + 1}" }
        assertEquals(listOf("first", "second"), loaded.map { it.text })
        assertEquals(listOf("Chat Outline", "Outline 2"), loaded.map { it.name })
        assertNull(loaded[0].promptId)
        assertFalse(loaded[0].customName)
        assertNull(ChatOutlines.legacyNumber(0))
        assertEquals(2, ChatOutlines.legacyNumber(1))
    }

    @Test
    fun newOutlinesAreNamedAfterThePromptWithTheLowestFreeNumber() {
        assertEquals("Chat Outline", ChatOutlines.nextName("Chat Outline", emptyList()))
        assertEquals("Chat Outline 1", ChatOutlines.nextName("Chat Outline", listOf("Chat Outline")))
        assertEquals(
            "Chat Outline 2",
            ChatOutlines.nextName("Chat Outline", listOf("Chat Outline", "Chat Outline 1"))
        )
        // A deleted number is reused before a higher one.
        assertEquals(
            "Chat Outline 1",
            ChatOutlines.nextName("Chat Outline", listOf("Chat Outline", "Chat Outline 2"))
        )
        val two = ChatOutlines.afterGenerateNew(listOf(outline("Chat Outline")), "new", "p", "Chat Outline")
        assertEquals(listOf("Chat Outline", "Chat Outline 1"), two.map { it.name })
        assertEquals("new", two[1].text)
        assertEquals("p", two[1].promptId)
    }

    @Test
    fun theLimitIsFiveOutlines() {
        val four = (1..4).map { outline("Outline $it") }
        assertFalse(ChatOutlines.isFull(four))
        assertTrue(ChatOutlines.isFull(four + outline("Outline 5")))
        assertEquals(5, ChatOutlines.MAX_OUTLINES)
    }

    @Test
    fun redoChangesOnlyItsTabAndRenamesForADifferentPrompt() {
        val outlines = listOf(outline("Chat Outline", "chat"), outline("Chat Outline 1", "chat"))
        val same = ChatOutlines.afterRedo(outlines, 1, "redone", "chat", "Chat Outline")
        assertEquals(listOf("Chat Outline", "Chat Outline 1"), same.map { it.name })
        assertEquals(listOf("# Chat Outline", "redone"), same.map { it.text })

        val other = ChatOutlines.afterRedo(outlines, 1, "notes", "notes", "Notes Outline")
        assertEquals("Notes Outline", other[1].name)
        assertEquals("notes", other[1].promptId)
        assertEquals(outlines[0], other[0])

        assertEquals(outlines, ChatOutlines.afterRedo(outlines, 3, "x", "chat", "Chat Outline"))
    }

    @Test
    fun aRedoNeverReplacesTheUsersOwnName() {
        val outlines = listOf(outline("Trip ideas", "chat", custom = true))
        val redone = ChatOutlines.afterRedo(outlines, 0, "notes", "notes", "Notes Outline")
        assertEquals("Trip ideas", redone[0].name)
        assertEquals("notes", redone[0].promptId)
    }

    @Test
    fun anOlderOutlineRedoneIsNamedAfterItsPrompt() {
        val outlines = listOf(ChatOutline("old", "Chat Outline"), ChatOutline("older", "Outline 2"))
        val redone = ChatOutlines.afterRedo(outlines, 1, "new", "chat", "Chat Outline")
        assertEquals("Chat Outline 1", redone[1].name)
    }

    @Test
    fun renameAndEditChangeOnlyTheirOwnPart() {
        val outlines = listOf(outline("A"), outline("B"))
        val renamed = ChatOutlines.afterRename(outlines, 1, "  Trip ideas ")
        assertEquals("Trip ideas", renamed[1].name)
        assertTrue(renamed[1].customName)
        assertEquals(outlines, ChatOutlines.afterRename(outlines, 1, "   "))

        val edited = ChatOutlines.afterEdit(outlines, 0, "changed")
        assertEquals("changed", edited[0].text)
        assertEquals("A", edited[0].name)
        assertEquals(outlines, ChatOutlines.afterEdit(outlines, 5, "x"))
    }

    @Test
    fun deleteNeedsMoreThanOneOutline() {
        val only = listOf(outline("A"))
        assertEquals(only, ChatOutlines.afterDelete(only, 0))
        val after = ChatOutlines.afterDelete(listOf(outline("A"), outline("B"), outline("C")), 1)
        assertEquals(listOf("A", "C"), after.map { it.name })
    }
}
