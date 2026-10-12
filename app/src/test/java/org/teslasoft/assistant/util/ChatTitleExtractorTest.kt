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

package org.teslasoft.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatTitleExtractorTest {

    @Test
    fun plainTitleIsKept() {
        assertEquals("Weekend Hiking Plans", ChatTitleExtractor.extract("Weekend Hiking Plans"))
    }

    @Test
    fun labelsAndQuotesAreRemoved() {
        assertEquals("Weekend Hiking Plans", ChatTitleExtractor.extract("Title: \"Weekend Hiking Plans\""))
        assertEquals("Weekend Hiking Plans", ChatTitleExtractor.extract("**Weekend Hiking Plans**"))
    }

    @Test
    fun thinkingIntroductionIsNeverTheTitle() {
        assertNull(ChatTitleExtractor.extract("Here's a thinking process:"))
    }

    @Test
    fun titleAfterWrittenOutThinkingIsFound() {
        val reply = """
            Here's a thinking process:
            1. The user is asking about planning a hike.
            2. The key topic is weekend hiking.
            3. Keep it short.

            Weekend Hiking Plans
        """.trimIndent()
        assertEquals("Weekend Hiking Plans", ChatTitleExtractor.extract(reply))
    }

    @Test
    fun thinkTagsAreLookedPast() {
        val reply = "<think>The user wants a title about recipes. Short.</think>\nQuick Pasta Recipes"
        assertEquals("Quick Pasta Recipes", ChatTitleExtractor.extract(reply))
    }

    @Test
    fun unfinishedThinkingYieldsNoTitle() {
        assertNull(ChatTitleExtractor.extract("<think>The user wants a title and I should consider"))
    }

    @Test
    fun longSentencesAreNotTitles() {
        assertNull(
            ChatTitleExtractor.extract(
                "The user is asking me to help them figure out what to pack for a long trip."
            )
        )
    }

    @Test
    fun emptyReplyYieldsNoTitle() {
        assertNull(ChatTitleExtractor.extract(null))
        assertNull(ChatTitleExtractor.extract("   \n "))
    }
}
