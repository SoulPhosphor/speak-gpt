package org.teslasoft.assistant.conversation.index

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModels
import org.teslasoft.assistant.preferences.memory.librarian.HfTokenizer
import org.teslasoft.assistant.preferences.memory.librarian.InputTooLongException

/**
 * Step 9 with the real EmbeddingGemma tokenizer and the model's 2,048-token
 * stored-text limit (G.5a rule 2, G.5b "Before a vector is made"): every
 * input is counted whole, prefix and special tokens included; one that does
 * not fit is recorded as failed and is never read cut off.
 *
 * Runs on the JVM when `GEMMA_TOKENIZER_JSON` points at the pinned
 * tokenizer (Android Checks downloads it); required on CI.
 */
class PassageEmbeddingGemmaTest {

    private lateinit var tokenizer: HfTokenizer
    private val model = EmbeddingModels.GEMMA_Q4

    @Before fun load() {
        val path = System.getenv("GEMMA_TOKENIZER_JSON")
        val present = !path.isNullOrBlank() && File(path).isFile
        if (System.getenv("CI") == "true") assertTrue("GEMMA_TOKENIZER_JSON must point at the tokenizer on CI", present)
        assumeTrue("GEMMA_TOKENIZER_JSON not set", present)
        tokenizer = HfTokenizer.load(File(path!!))
    }

    /** Counts with the real tokenizer and refuses, like the real model, anything over the limit. */
    private inner class RealCountVectors : PassageVectorReader {
        override val vectorModel = model.embeddingTag
        override val vectorDimensions = model.dimensions
        override val tokenLimit = model.storedTextMaxTokens
        val inputs = ArrayList<String>()
        override fun countTokens(modelInput: String) = tokenizer.countTokens(modelInput)
        override fun read(modelInput: String): FloatArray {
            val tokens = countTokens(modelInput)
            if (tokens > tokenLimit) throw InputTooLongException(tokens, tokenLimit)
            inputs.add(modelInput)
            return FloatArray(vectorDimensions) { 0.5f }
        }
    }

    private object Unpaused : EmbeddingPauseState { override var paused = false }

    private fun indexAndEmbed(
        messages: List<IndexSourceMessage>,
        searchReader: SearchTextReader,
        target: Int,
        overlap: Int
    ): Pair<MemoryIndex, RealCountVectors> {
        val index = MemoryIndex()
        val chats = MemoryChats().apply { put("chat", messages) }
        val outcome = ConversationIndexUpdater(index, searchReader, target, overlap)
            .update("chat", chats.version("chat"), messages, { chats.version("chat") })
        assertTrue("$outcome", outcome is ConversationIndexUpdater.Outcome.Updated)
        val vectors = RealCountVectors()
        val pass = PassageEmbeddingWorker(index, chats, searchReader, vectors, Unpaused).runPass()
        assertTrue("$pass", pass is PassageEmbeddingWorker.Outcome.Finished)
        return index to vectors
    }

    private val messages = listOf(
        IndexSourceMessage("u1", false, (1..150).joinToString("\n\n") { "Paragraph $it: we packed box $it before moving to Leeds." },
            false, null, 1L, null),
        IndexSourceMessage("a1", true, "https://example.invalid/" + "x7".repeat(6_000), false, MessageCompletionState.DONE, 2L, null),
        IndexSourceMessage("u2", false, "我们今天去公园散步然后在湖边吃午饭天气很好".repeat(300), false, null, 3L, null),
        IndexSourceMessage("a2", true, "👨‍👩‍👧‍👦 family time 🎉 ".repeat(800), false, MessageCompletionState.DONE, 4L, null)
    )

    @Test fun everyInputIsReadWholeAndFitsTheRealLimit() {
        val real = SearchTextReader.forEmbeddingModel(model, tokenizer)
        for ((target, overlap) in listOf(2_000 to 2_000, 256 to 40)) {
            val (index, vectors) = indexAndEmbed(messages, real, target, overlap)
            val progress = index.chatProgress("chat")
            assertEquals("$target", progress.passages, progress.made)
            assertEquals(0, progress.failed)
            assertEquals(index.vectors.size, vectors.inputs.size)
            val expected = messages.flatMap { message ->
                val title = if (message.isBot) SpeakerTitlePolicy.COMPANION else SpeakerTitlePolicy.HUMAN
                PassageSearchText.build(message.modelText, title, real, target, overlap).passages.map { it.modelInput }
            }.toSet()
            assertEquals(expected, vectors.inputs.toSet())
            for (input in vectors.inputs) {
                val tokens = tokenizer.countTokens(input)
                assertTrue("$tokens tokens", tokens <= model.storedTextMaxTokens)
                // Nothing was cut: encoding with the limit keeps every token.
                assertEquals(tokens, tokenizer.encode(input, model.storedTextMaxTokens).size)
            }
        }
    }

    @Test fun anInputOverTheRealLimitIsRecordedAsFailedAndNeverRead() {
        // Cut with a measure that undercounts, as a changed tokenizer would:
        // the real count then exceeds the limit for the large passages.
        val undercounting = SearchTextReader(
            vectorModel = model.embeddingTag,
            vectorDimensions = model.dimensions,
            hardLimit = model.storedTextMaxTokens,
            prefixFor = { title -> model.documentPrefix.replaceFirst("title: none", "title: $title") },
            countModelInput = { tokenizer.countTokens(it) / 4 }
        )
        assertEquals(SearchTextReader.forEmbeddingModel(model, tokenizer).key, undercounting.key)
        val (index, vectors) = indexAndEmbed(messages, undercounting, 2_000, 0)
        val progress = index.chatProgress("chat")
        assertTrue(progress.failed > 0)
        assertEquals(progress.passages, progress.made + progress.failed)
        for (input in vectors.inputs) assertTrue(tokenizer.countTokens(input) <= model.storedTextMaxTokens)
        for ((key, vector) in index.vectors) {
            if (vector.state == ConversationIndexStore.STATE_FAILED) {
                assertTrue(vectors.inputs.none { IndexFingerprint.of(it) == key.searchFingerprint })
            }
        }
    }
}
