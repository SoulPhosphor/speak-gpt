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

package org.teslasoft.assistant.preferences.includes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentIncludeContextTest {

    private fun include(id: String, form: IncludeForm = IncludeForm.FULL) = ChatInclude(
        id = id,
        fileName = "$id.txt",
        kind = IncludeKind.TXT,
        form = form,
        fullText = "full $id",
        condensedText = if (form == IncludeForm.CONDENSED) "short $id" else null,
        artifactLine = if (form == IncludeForm.ARTIFACT) "bookmark $id" else null
    )

    private fun message(isBot: Boolean, vararg includes: ChatInclude): HashMap<String, Any> =
        hashMapOf<String, Any>(
            "message" to if (isBot) "reply" else "turn",
            "isBot" to isBot
        ).apply {
            if (includes.isNotEmpty()) {
                this[PersistentIncludeContext.INCLUDES_KEY] = ChatInclude.listToJson(includes.toList())
            }
        }

    @Test
    fun laterUserRowsDeriveEarlierIncludesWithoutWritingToLaterRows() {
        val first = message(false, include("document"))
        val assistant = message(true)
        val later = message(false, include("new-on-this-turn"))
        val messages = arrayListOf(first, assistant, later)

        assertEquals(
            listOf("document"),
            PersistentIncludeContext.earlierForUserMessage(messages, 2).map { it.id }
        )
        val laterIncludes = ChatInclude.listFromJson(
            later[PersistentIncludeContext.INCLUDES_KEY]?.toString()
        )
        assertEquals(listOf("new-on-this-turn"), laterIncludes.map { it.id })
        assertFalse(laterIncludes.any { it.id == "document" })
        assertTrue(
            PersistentIncludeContext.earlierForUserMessage(messages, 0).isEmpty()
        )
    }

    @Test
    fun assistantRowsNeverReceiveInheritedIncludes() {
        val messages = arrayListOf(
            message(false, include("document")),
            message(true),
            message(false)
        )

        assertTrue(
            PersistentIncludeContext.earlierForUserMessage(messages, 1).isEmpty()
        )
    }

    @Test
    fun allSentIncludesPreserveCanonicalOrderAndDeduplicateIds() {
        val messages = arrayListOf(
            message(false, include("full")),
            message(true, include("ignored")),
            message(false, include("full", IncludeForm.CONDENSED), include("artifact", IncludeForm.ARTIFACT))
        )

        assertEquals(
            listOf("full", "artifact"),
            PersistentIncludeContext.allSent(messages).map { it.id }
        )
    }
}
