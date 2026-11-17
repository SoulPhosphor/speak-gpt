package org.teslasoft.assistant.preferences.memory.librarian

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemoryTokenBudgetTest {
    private fun tokenizer(): HfTokenizer {
        val vocab = JSONObject().put("<unk>", 0).put("<bos>", 1).put("<eos>", 2)
        for (code in 0..255) vocab.put("<0x%02X>".format(code), code + 3)
        for (code in 32..126) vocab.put(code.toChar().toString(), code + 300)
        return HfTokenizer.fromJson(JSONObject().apply {
            put("model", JSONObject().put("type", "BPE").put("unk_token", "<unk>")
                .put("byte_fallback", true).put("vocab", vocab).put("merges", JSONArray()))
            put("post_processor", JSONObject().put("type", "TemplateProcessing")
                .put("single", JSONArray().put(JSONObject().put("SpecialToken", JSONObject().put("id", "<bos>")))
                    .put(JSONObject().put("Sequence", JSONObject().put("id", "A")))
                    .put(JSONObject().put("SpecialToken", JSONObject().put("id", "<eos>"))))
                .put("special_tokens", JSONObject()
                    .put("<bos>", JSONObject().put("ids", JSONArray(listOf(1))))
                    .put("<eos>", JSONObject().put("ids", JSONArray(listOf(2))))))
        }.toString())
    }

    @Test fun fullDocumentIncludesExtraTextTagsPrefixAndSpecialTokens() {
        val tokenizer = tokenizer()
        val prefix = EmbeddingModels.GEMMA_Q4.documentPrefix
        val input = MemoryTokenBudget.Input("memory", "extra search", listOf("tag one", "tag two"))
        val reader = MemoryTokenBudget.Reader("fixture", 2048) { tokenizer.countTokens(prefix + it) }
        val expected = prefix + "memory\nextra search\ntag one, tag two"
        assertEquals(tokenizer.encode(expected, Int.MAX_VALUE).size, reader.measure(input).tokens)
        assertEquals(expected.length + 2, reader.measure(input).tokens)
        assertTrue(reader.measure(input).tokens > tokenizer.countTokens(input.content))
    }

    @Test fun exactCapacityFitsAndOneTokenOverDoesNot() {
        val tokenizer = tokenizer()
        val reader = MemoryTokenBudget.Reader("fixture", 10) { tokenizer.countTokens(it) }
        assertTrue(reader.measure(MemoryTokenBudget.Input("abcdefgh")).fits)
        assertFalse(reader.measure(MemoryTokenBudget.Input("abcdefghi")).fits)
        assertEquals(100, reader.measure(MemoryTokenBudget.Input("abcdefghi")).percent)
    }

    @Test fun wordCountDoesNotDetermineWhetherAMemoryFits() {
        val roomy = MemoryTokenBudget.Reader("roomy", 10000) { it.length + 2 }
        val compact = MemoryTokenBudget.Input("a ".repeat(1600))
        assertEquals(1600, MemoryTokenBudget.words(compact.content))
        assertTrue(roomy.measure(compact).fits)
        val tokenizer = tokenizer()
        val small = MemoryTokenBudget.Reader("small", 20) { tokenizer.countTokens(it) }
        val unusual = MemoryTokenBudget.Input("龍".repeat(20))
        assertEquals(1, MemoryTokenBudget.words(unusual.content))
        assertFalse(small.measure(unusual).fits)
    }

    @Test fun tagsCanPushTheSameContentOverCapacity() {
        val reader = MemoryTokenBudget.Reader("fixture", 10) { it.length + 2 }
        assertTrue(reader.measure(MemoryTokenBudget.Input("memory")).fits)
        assertFalse(reader.measure(MemoryTokenBudget.Input("memory", tags = listOf("long tag"))).fits)
    }

    @Test fun switchingModelsChangesTheLimitWithoutChangingTheInput() {
        val input = MemoryTokenBudget.Input("same memory")
        assertFalse(MemoryTokenBudget.Reader("small", 8) { it.length }.measure(input).fits)
        assertTrue(MemoryTokenBudget.Reader("large", 30) { it.length }.measure(input).fits)
        assertEquals("same memory", input.content)
    }

    @Test fun wordCounterHandlesUnicodeWhitespaceAndEmptyText() {
        assertEquals(0, MemoryTokenBudget.words(" \n\t"))
        assertEquals(4, MemoryTokenBudget.words("one\u00a0two\u2003three\nfour"))
    }
}
