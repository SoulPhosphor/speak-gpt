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

/** §1: never display or copy an API key or authorization header. */
class SummarizerDetailSanitizerTest {

    @Test
    fun bearerTokensAndKeysAreRemoved() {
        val out = SummarizerDetailSanitizer.sanitize(
            "Request failed. Authorization: Bearer sk-abcdef1234567890abcdef and header x-api-key: topsecretvalue123"
        )!!
        assertFalse(out.contains("sk-abcdef1234567890abcdef"))
        assertFalse(out.contains("topsecretvalue123"))
        assertTrue(out.contains("[removed]"))
    }

    @Test
    fun ordinaryProviderMessagesPassThrough() {
        assertEquals(
            "The model `gpt-x` does not exist",
            SummarizerDetailSanitizer.sanitize("The model `gpt-x` does not exist")
        )
    }

    @Test
    fun blankDetailBecomesNull() {
        assertNull(SummarizerDetailSanitizer.sanitize(null))
        assertNull(SummarizerDetailSanitizer.sanitize("   "))
    }

    @Test
    fun oversizedDetailIsTruncated() {
        val out = SummarizerDetailSanitizer.sanitize("x".repeat(10000))!!
        assertTrue(out.length < 5000)
    }
}
