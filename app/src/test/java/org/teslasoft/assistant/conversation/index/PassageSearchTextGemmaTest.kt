package org.teslasoft.assistant.conversation.index

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.teslasoft.assistant.conversation.index.SpeakerTitlePolicy.Companion.COMPANION
import org.teslasoft.assistant.conversation.index.SpeakerTitlePolicy.Companion.HUMAN
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModels
import org.teslasoft.assistant.preferences.memory.librarian.HfTokenizer

/**
 * Memory development plan G.5b, "With the search text, in Step 8", with the
 * real EmbeddingGemma tokenizer and the model's 2,048-token stored-text limit.
 *
 * The tokenizer is pure Kotlin, so this runs on the JVM; on the CI emulator
 * the same work exceeded the suite's per-class time limit. Android Checks
 * downloads the pinned tokenizer and sets `GEMMA_TOKENIZER_JSON`; on CI a
 * missing file fails the test. Locally:
 *
 *     GEMMA_TOKENIZER_JSON=/path/to/tokenizer.json ./gradlew testDebugUnitTest \
 *         --tests '*PassageSearchTextGemmaTest*'
 */
class PassageSearchTextGemmaTest {

    private lateinit var tokenizer: HfTokenizer

    @Before fun load() {
        val path = System.getenv("GEMMA_TOKENIZER_JSON")
        val present = !path.isNullOrBlank() && File(path).isFile
        if (System.getenv("CI") == "true") assertTrue("GEMMA_TOKENIZER_JSON must point at the tokenizer on CI", present)
        assumeTrue("GEMMA_TOKENIZER_JSON not set", present)
        tokenizer = HfTokenizer.load(File(path!!))
    }

    @Test fun realTokenizerSearchTextsFitTheModelAndHoldOnlyTheirOwnMessage() {
        val model = EmbeddingModels.GEMMA_Q4
        val real = SearchTextReader.forEmbeddingModel(model, tokenizer)
        assertEquals(model.storedTextMaxTokens, real.hardLimit)
        assertEquals("title: Human | text: hello", real.modelInput(HUMAN, "hello"))

        val messages = mapOf(
            "ordinary" to (1..120).joinToString("\n\n") { "Paragraph $it: we packed box $it before moving to Leeds." },
            "no spaces" to "https://example.invalid/" + "x7".repeat(6_000),
            "Chinese" to "我们今天去公园散步然后在湖边吃午饭天气很好".repeat(300),
            "emoji" to "👨‍👩‍👧‍👦 family time 🎉 ".repeat(800),
            "code" to (1..400).joinToString("\n") { "fun item$it(value: Int) = value * $it" }
        )
        for ((target, overlap) in listOf(256 to 40, 2_000 to 2_000, 64 to 10)) {
            for ((name, text) in messages) {
                val built = PassageSearchText.build(text, COMPANION, real, target, overlap).passages
                if (target == 64) assertTrue("$name is cut into several passages", built.size > 1)
                assertEquals(text, built.joinToString("") { text.substring(it.start, it.end) })
                for ((index, passage) in built.withIndex()) {
                    val measured = tokenizer.countTokens(passage.modelInput)
                    assertTrue("$name/$target: $measured tokens", measured <= model.storedTextMaxTokens)
                    assertEquals(measured, passage.searchTokens)
                    assertEquals(model.documentPrefix.replaceFirst("title: none", "title: Companion") + passage.searchText, passage.modelInput)
                    assertEquals(text.substring(passage.searchStart, passage.end), passage.searchText)
                    if (index > 0) assertTrue(passage.searchStart >= built[index - 1].start)
                }
            }
        }
    }
}
