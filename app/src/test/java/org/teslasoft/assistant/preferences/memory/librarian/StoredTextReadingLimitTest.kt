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

package org.teslasoft.assistant.preferences.memory.librarian

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Token counting and the two reading limits (conversation_recall_plan.md
 * Step 1, G33): counts include the template's special tokens and never cut
 * anything; stored text is read up to 2,048 tokens while the message being
 * sent keeps 512; text that fits inside 512 tokens is encoded exactly as
 * before, so its vector cannot change.
 */
class StoredTextReadingLimitTest {

    // ids: <unk>=0 <bos>=1 <eos>=2 ▁=3 a=4 ▁a=5 b=6 ▁b=7
    // Each word "a" or "b" becomes one token, so a text of N words is N
    // content tokens plus <bos> and <eos>.
    private val tokenizer = HfTokenizer.fromJson(
        """
        {
          "added_tokens": [
            {"id": 0, "content": "<unk>", "special": true},
            {"id": 1, "content": "<bos>", "special": true},
            {"id": 2, "content": "<eos>", "special": true}
          ],
          "normalizer": {"type": "Sequence", "normalizers": [
            {"type": "Prepend", "prepend": "▁"},
            {"type": "Replace", "pattern": {"String": " "}, "content": "▁"}
          ]},
          "pre_tokenizer": {"type": "Metaspace", "replacement": "▁", "prepend_scheme": "never", "split": true},
          "post_processor": {
            "type": "TemplateProcessing",
            "single": [
              {"SpecialToken": {"id": "<bos>", "type_id": 0}},
              {"Sequence": {"id": "A", "type_id": 0}},
              {"SpecialToken": {"id": "<eos>", "type_id": 0}}
            ],
            "special_tokens": {
              "<bos>": {"id": "<bos>", "ids": [1], "tokens": ["<bos>"]},
              "<eos>": {"id": "<eos>", "ids": [2], "tokens": ["<eos>"]}
            }
          },
          "model": {
            "type": "BPE",
            "unk_token": "<unk>",
            "vocab": {"<unk>": 0, "<bos>": 1, "<eos>": 2, "▁": 3, "a": 4, "▁a": 5, "b": 6, "▁b": 7},
            "merges": ["▁ a", "▁ b"]
          }
        }
        """.trimIndent()
    )

    private fun words(count: Int, last: String = "a"): String =
        (List(count - 1) { "a" } + last).joinToString(" ")

    @Test
    fun countIncludesTheTemplateSpecialTokens() {
        assertEquals(3, tokenizer.countTokens("a"))
        assertEquals(12, tokenizer.countTokens(words(10)))
    }

    @Test
    fun countMatchesTheFullEncodingAndCutsNothing() {
        for (text in listOf("a", "b a b", words(511), words(1_000), words(3_000))) {
            assertEquals(tokenizer.encode(text, Int.MAX_VALUE).size, tokenizer.countTokens(text))
        }
        // Far beyond every limit: still the true count.
        assertEquals(3_002, tokenizer.countTokens(words(3_000)))
    }

    @Test
    fun aMemoryLongerThan512TokensIsReadInFullAtTheStoredTextLimit() {
        val memory = words(1_000, last = "b")
        val stored = tokenizer.encode(memory, EmbeddingModels.GEMMA_Q4.storedTextMaxTokens)
        assertEquals(1_002, stored.size)
        // The last word is read, and the end marker is kept.
        assertEquals(7L, stored[stored.size - 2])
        assertEquals(2L, stored.last())

        // The previous limit cut it, losing the last word.
        val previous = tokenizer.encode(memory, Librarian.PREVIOUS_STORED_TEXT_TOKEN_LIMIT)
        assertEquals(512, previous.size)
        assertEquals(5L, previous[previous.size - 2])
    }

    @Test
    fun textThatFitsTheOldLimitEncodesExactlyAsBefore() {
        for (count in listOf(1, 10, 509, 510)) {
            val text = words(count, last = "b")
            assertArrayEquals(
                tokenizer.encode(text, Librarian.PREVIOUS_STORED_TEXT_TOKEN_LIMIT),
                tokenizer.encode(text, EmbeddingModels.GEMMA_Q4.storedTextMaxTokens)
            )
        }
    }

    @Test
    fun theMessageBeingSentKeepsThe512Limit() {
        for (model in EmbeddingModels.ALL) {
            assertEquals(512, model.maxTokens)
            assertEquals(2_048, model.storedTextMaxTokens)
        }
        assertEquals(512, tokenizer.encode(words(1_000), EmbeddingModels.GEMMA_Q4.maxTokens).size)
    }

    @Test
    fun onlyMemoriesCutAtTheOldLimitAreReadAgain() {
        assertFalse(Librarian.needsLongerRead(1))
        assertFalse(Librarian.needsLongerRead(512))
        assertTrue(Librarian.needsLongerRead(513))
        assertTrue(Librarian.needsLongerRead(2_049))
        assertFalse(Librarian.needsLongerRead(tokenizer.countTokens(words(510))))
        assertTrue(Librarian.needsLongerRead(tokenizer.countTokens(words(511))))
    }

    @Test
    fun theRereadMarkerNamesTheLimitAndTheEmbeddingKey() {
        val key = RetrievalDocument.effectiveKey(EmbeddingModels.GEMMA_Q4.embeddingTag)
        val marker = Librarian.storedTextReadMarker(2_048, key)
        assertEquals("2048|$key", marker)
        assertFalse(marker == Librarian.storedTextReadMarker(2_048, RetrievalDocument.effectiveKey("other-256")))
    }
}
