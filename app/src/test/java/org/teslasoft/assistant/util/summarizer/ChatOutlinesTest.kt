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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatOutlinesTest {

    @Test
    fun jsonRoundTripKeepsOrderAndMarkdown() {
        val outlines = listOf("# One\n- a", "## Two \"quoted\"")
        assertEquals(outlines, ChatOutlines.fromJson(ChatOutlines.toJson(outlines)))
        assertEquals("", ChatOutlines.toJson(emptyList()))
        assertTrue(ChatOutlines.fromJson("").isEmpty())
        assertTrue(ChatOutlines.fromJson("not json").isEmpty())
    }

    @Test
    fun generateNewAddsATabAndReplaceAllLeavesOne() {
        val two = ChatOutlines.afterGenerateNew(listOf("first"), "second")
        assertEquals(listOf("first", "second"), two)
        assertEquals(listOf("fresh"), ChatOutlines.afterReplaceAll("fresh"))
    }

    @Test
    fun redoChangesOnlyItsTab() {
        assertEquals(
            listOf("a", "B", "c"),
            ChatOutlines.afterReplace(listOf("a", "b", "c"), 1, "B")
        )
        assertEquals(listOf("a"), ChatOutlines.afterReplace(listOf("a"), 3, "x"))
    }

    @Test
    fun deleteNeedsMoreThanOneOutlineAndRenumbersByPosition() {
        assertEquals(listOf("only"), ChatOutlines.afterDelete(listOf("only"), 0))
        val after = ChatOutlines.afterDelete(listOf("a", "b", "c"), 1)
        assertEquals(listOf("a", "c"), after)
        assertNull(ChatOutlines.numberedLabel(0))
        assertEquals(2, ChatOutlines.numberedLabel(1))
    }
}
