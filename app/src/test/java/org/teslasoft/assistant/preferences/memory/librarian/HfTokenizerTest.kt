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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure-Kotlin tokenizer.json encoder with synthetic
 * fixtures shaped like real HF files: SentencePiece-BPE-with-byte-fallback
 * (the Gemma family) and Unigram (the XLM-R/BGE-M3 family). These can't prove
 * the encoder matches the real 262k-entry Gemma tokenizer — that's what the
 * Librarian's on-device semantic self-check is for — but they pin the
 * mechanics: merge order, byte fallback, unk fusing, metaspace splitting,
 * template specials, truncation, and both merge serializations.
 */
class HfTokenizerTest {

    /* -------------------------------- BPE ------------------------------- */

    // vocab ids: <unk>=0 <bos>=1 ▁=2 h=3 i=4 ▁h=5 ▁hi=6 <0xE2>=7 <0x82>=8 <0xAC>=9
    private fun bpeJson(
        mergesJson: String,
        byteFallback: Boolean = false,
        fuseUnk: Boolean = false
    ): String = """
        {
          "added_tokens": [
            {"id": 0, "content": "<unk>", "special": true},
            {"id": 1, "content": "<bos>", "special": true}
          ],
          "normalizer": {"type": "Sequence", "normalizers": [
            {"type": "Prepend", "prepend": "▁"},
            {"type": "Replace", "pattern": {"String": " "}, "content": "▁"}
          ]},
          "pre_tokenizer": null,
          "post_processor": {
            "type": "TemplateProcessing",
            "single": [
              {"SpecialToken": {"id": "<bos>", "type_id": 0}},
              {"Sequence": {"id": "A", "type_id": 0}}
            ],
            "special_tokens": {"<bos>": {"id": "<bos>", "ids": [1], "tokens": ["<bos>"]}}
          },
          "model": {
            "type": "BPE",
            "unk_token": "<unk>",
            "fuse_unk": $fuseUnk,
            "byte_fallback": $byteFallback,
            "vocab": {"<unk>": 0, "<bos>": 1, "▁": 2, "h": 3, "i": 4, "▁h": 5, "▁hi": 6,
                      "<0xE2>": 7, "<0x82>": 8, "<0xAC>": 9},
            "merges": $mergesJson
          }
        }
    """.trimIndent()

    private val mergesAsStrings = """["▁ h", "▁h i"]"""
    private val mergesAsArrays = """[["▁", "h"], ["▁h", "i"]]"""

    @Test
    fun bpeMergesInRankOrderWithBosTemplate() {
        val tok = HfTokenizer.fromJson(bpeJson(mergesAsStrings))
        // "hi hi" -> "▁hi▁hi" -> ▁h+i twice -> ▁hi ▁hi, plus <bos> from the template.
        assertArrayEquals(longArrayOf(1, 6, 6), tok.encode("hi hi", 64))
    }

    @Test
    fun bpeMergesArraySerializationMatchesStringForm() {
        val a = HfTokenizer.fromJson(bpeJson(mergesAsStrings)).encode("hi hi", 64)
        val b = HfTokenizer.fromJson(bpeJson(mergesAsArrays)).encode("hi hi", 64)
        assertArrayEquals(a, b)
    }

    @Test
    fun bpeByteFallbackEmitsUtf8ByteTokens() {
        val tok = HfTokenizer.fromJson(bpeJson(mergesAsStrings, byteFallback = true))
        // "€" is U+20AC = E2 82 AC in UTF-8; "▁" prepended by the normalizer.
        assertArrayEquals(longArrayOf(1, 2, 7, 8, 9), tok.encode("€", 64))
    }

    @Test
    fun bpeUnknownsFuseIntoOneUnk() {
        val tok = HfTokenizer.fromJson(bpeJson(mergesAsStrings, fuseUnk = true))
        // Two adjacent unknown codepoints collapse into a single <unk>.
        assertArrayEquals(longArrayOf(1, 2, 0), tok.encode("€₤", 64))
    }

    @Test
    fun truncationCutsContentButKeepsTemplateSpecials() {
        val tok = HfTokenizer.fromJson(bpeJson(mergesAsStrings))
        // Budget 2 = <bos> + 1 content token; the second ▁hi is dropped.
        assertArrayEquals(longArrayOf(1, 6), tok.encode("hi hi", 2))
    }

    @Test
    fun distinctTextsTokenizeDistinctlyAndInRange() {
        val tok = HfTokenizer.fromJson(bpeJson(mergesAsStrings, byteFallback = true))
        val a = tok.encode("hi", 64)
        val b = tok.encode("hi hi", 64)
        assertFalse(a.contentEquals(b))
        for (id in a + b) assertTrue(id in 0 until tok.vocabSize.toLong())
    }

    /* ------------------------------ Unigram ------------------------------ */

    // pieces (id = array index): <unk>=0 ▁ab=1 ▁a=2 b=3 ▁=4 a=5
    private val unigramJson = """
        {
          "normalizer": null,
          "pre_tokenizer": {"type": "Metaspace", "replacement": "▁", "prepend_scheme": "always", "split": true},
          "post_processor": null,
          "model": {
            "type": "Unigram",
            "unk_id": 0,
            "byte_fallback": false,
            "vocab": [["<unk>", 0.0], ["▁ab", -1.0], ["▁a", -2.0], ["b", -2.5], ["▁", -3.0], ["a", -3.0]]
          }
        }
    """.trimIndent()

    @Test
    fun unigramViterbiPicksBestSegmentation() {
        val tok = HfTokenizer.fromJson(unigramJson)
        // "▁ab" (-1.0) beats "▁a"+"b" (-4.5) and "▁"+"a"+"b" (-8.5).
        assertArrayEquals(longArrayOf(1), tok.encode("ab", 64))
    }

    @Test
    fun unigramMetaspaceSplitsWords() {
        val tok = HfTokenizer.fromJson(unigramJson)
        assertArrayEquals(longArrayOf(1, 1), tok.encode("ab ab", 64))
    }

    @Test
    fun unigramUnknownCodepointFallsToUnkId() {
        val tok = HfTokenizer.fromJson(unigramJson)
        // "z" is not a piece: "▁" (id 4) then <unk> (id 0).
        assertArrayEquals(longArrayOf(4, 0), tok.encode("z", 64))
    }

    /* ---------------------------- strictness ----------------------------- */

    @Test
    fun unsupportedModelTypeThrows() {
        val json = """{"model": {"type": "WordPiece", "vocab": {"a": 0}}}"""
        assertThrows(IllegalStateException::class.java) { HfTokenizer.fromJson(json) }
    }

    @Test
    fun unsupportedNormalizerThrows() {
        val json = """
            {
              "normalizer": {"type": "SomethingNew"},
              "model": {"type": "BPE", "vocab": {"a": 0}, "merges": []}
            }
        """.trimIndent()
        assertThrows(IllegalStateException::class.java) { HfTokenizer.fromJson(json) }
    }

    @Test
    fun unsupportedPostProcessorThrows() {
        val json = """
            {
              "post_processor": {"type": "ByteLevel"},
              "model": {"type": "BPE", "vocab": {"a": 0}, "merges": []}
            }
        """.trimIndent()
        assertThrows(IllegalStateException::class.java) { HfTokenizer.fromJson(json) }
    }

    @Test
    fun missingModelSectionThrows() {
        assertThrows(IllegalStateException::class.java) { HfTokenizer.fromJson("""{"version": "1.0"}""") }
    }

    @Test
    fun vocabSizeCoversAddedTokensAboveModelVocab() {
        val json = """
            {
              "added_tokens": [{"id": 99, "content": "<extra>", "special": true}],
              "model": {"type": "BPE", "vocab": {"a": 0, "b": 1}, "merges": []}
            }
        """.trimIndent()
        assertEquals(100, HfTokenizer.fromJson(json).vocabSize)
    }
}
