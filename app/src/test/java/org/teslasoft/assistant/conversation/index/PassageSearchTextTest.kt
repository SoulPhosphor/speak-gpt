package org.teslasoft.assistant.conversation.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.conversation.index.SpeakerTitlePolicy.Companion.COMPANION
import org.teslasoft.assistant.conversation.index.SpeakerTitlePolicy.Companion.HUMAN

/**
 * Memory development plan G.5b, "With the search text, in Step 8", with a
 * stand-in size function. The same checks run with the real EmbeddingGemma
 * tokenizer in `ConversationIndexStoreInstrumentedTest`.
 */
class PassageSearchTextTest {

    /** A non-additive token-like size: short letter runs, digits and punctuation, plus two specials. */
    private val tokenLike: (String) -> Int = { text ->
        Regex("""\p{L}{1,4}|\p{N}{1,3}|[^\s\p{L}\p{N}]""").findAll(text).count() + 2
    }

    private fun reader(hardLimit: Int = 120) = SearchTextReader(
        vectorModel = "test-model-8",
        vectorDimensions = 8,
        hardLimit = hardLimit,
        prefixFor = { "title: $it | text: " },
        countModelInput = tokenLike
    )

    private val samples = mapOf(
        "ordinary" to (1..80).joinToString("\n\n") { "Paragraph $it talks about moving house and packing box number $it." },
        "one long paragraph" to (1..400).joinToString(" ") { "word$it" },
        "no spaces" to "x".repeat(2_000),
        "Chinese" to "我们今天去公园散步然后在湖边吃午饭天气很好".repeat(40),
        "emoji" to "👨‍👩‍👧‍👦 family time 🎉 ".repeat(120)
    )

    @Test fun noSearchTextExceedsTheModelLimit() {
        for (hardLimit in listOf(40, 120, 400)) {
            val reader = reader(hardLimit)
            for ((name, text) in samples) {
                for (overlap in listOf(0, 5, 40, 1_000)) {
                    val built = PassageSearchText.build(text, COMPANION, reader, hardLimit / 2, overlap)
                    for (passage in built.passages) {
                        assertTrue("$name/$hardLimit/$overlap", tokenLike(passage.modelInput) <= hardLimit)
                        assertEquals(tokenLike(passage.modelInput), passage.searchTokens)
                    }
                }
            }
        }
    }

    @Test fun searchTextHoldsOnlyItsOwnMessage() {
        val chat = listOf(
            "Alpha message: I moved to Leeds last spring and bought a red bicycle.",
            "Bravo message: the conference in Lisbon was postponed until autumn.",
            (1..200).joinToString(" ") { "charlie$it" }
        )
        val reader = reader(60)
        for ((index, text) in chat.withIndex()) {
            val others = chat.filterIndexed { other, _ -> other != index }
                .flatMap { Regex("""\p{L}+\d*""").findAll(it).map { m -> m.value }.toList() }
                .filter { word -> !text.contains(word) }
            for (passage in PassageSearchText.build(text, HUMAN, reader, 30, 8).passages) {
                assertEquals(text.substring(passage.searchStart, passage.end), passage.searchText)
                assertEquals(reader.modelInput(HUMAN, passage.searchText), passage.modelInput)
                for (word in others) assertFalse(word, passage.searchText.contains(word))
            }
        }
    }

    @Test fun overlapHoldsOnlyWordsFromThePassageBeforeItInTheSameMessage() {
        val text = (1..300).joinToString(" ") { "w$it" }
        val built = PassageSearchText.build(text, COMPANION, reader(200), 40, 6).passages
        assertTrue(built.size > 3)
        assertEquals(built.first().start, built.first().searchStart)
        for (index in 1 until built.size) {
            val previous = built[index - 1]
            val passage = built[index]
            assertTrue(passage.searchStart >= previous.start)
            assertTrue(passage.searchStart <= passage.start)
            val overlap = text.substring(passage.searchStart, passage.start)
            val words = overlap.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            assertEquals("the overlap carries the last 6 words that fit", 6, words.size)
            assertTrue(text.substring(previous.start, previous.end).endsWith(overlap))
        }
    }

    @Test fun overlapShrinksToFitAndOriginalPositionsStillCoverTheMessageOnce() {
        val text = (1..300).joinToString(" ") { "w$it" }
        val hardLimit = 50
        val built = PassageSearchText.build(text, COMPANION, reader(hardLimit), hardLimit - 2, 1_000).passages
        assertEquals(0, built.first().start)
        assertEquals(text.length, built.last().end)
        for (index in 1 until built.size) {
            assertEquals(built[index - 1].end, built[index].start)
            assertTrue(tokenLike(built[index].modelInput) <= hardLimit)
        }
        assertEquals(text, built.joinToString("") { text.substring(it.start, it.end) })
    }

    @Test fun aTargetNoLargerThanThePrefixIsRefused() {
        val prefixOnly = reader().count(COMPANION, "")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PassageSearchText.build("Some words.", COMPANION, reader(), prefixOnly, 0)
        }
    }

    @Test fun zeroOverlapAndSpeakerLabelAreHonoured() {
        val text = (1..200).joinToString(" ") { "w$it" }
        val user = PassageSearchText.build(text, HUMAN, reader(), 30, 0).passages
        val ai = PassageSearchText.build(text, COMPANION, reader(), 30, 0).passages
        user.forEach { assertEquals(it.start, it.searchStart) }
        assertTrue(user.first().modelInput.startsWith("title: Human | text: "))
        assertTrue(ai.first().modelInput.startsWith("title: Companion | text: "))
        // The same words said by the other speaker are a different search text.
        assertFalse(user.first().searchFingerprint == ai.first().searchFingerprint)
    }

    @Test fun keywordsAreThePassagesOwnWordsOnly() {
        val text = "Moving boxes everywhere.\n\nThe red bicycle stays in Leeds."
        // Each paragraph fits the target alone; together they do not.
        val target = maxOf(
            reader().count(HUMAN, "Moving boxes everywhere.\n\n"),
            reader().count(HUMAN, "The red bicycle stays in Leeds.")
        )
        val passages = PassageSearchText.build(text, HUMAN, reader(), target, 8).passages
        assertEquals(2, passages.size)
        assertEquals("mov box everywhere", passages[0].keywords)
        assertEquals("red bicycle stay leed", passages[1].keywords)
    }

    @Test fun theSameMessageAlwaysBuildsTheSameSearchTexts() {
        for ((_, text) in samples) {
            assertEquals(
                PassageSearchText.build(text, COMPANION, reader(), 60, 8),
                PassageSearchText.build(text, COMPANION, reader(), 60, 8)
            )
        }
    }
}
