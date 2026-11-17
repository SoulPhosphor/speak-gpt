package org.teslasoft.assistant.preferences.memory.librarian

import org.junit.Assert.*
import org.junit.Test

class MemoryProposalSplitterTest {
    private val reader = MemoryTokenBudget.Reader("fixture", 80) { it.toByteArray(Charsets.UTF_8).size + 5 }

    @Test fun allOriginalTextAndTagsSurviveAnOversizedProposal() {
        val text = (1..200).joinToString("\n\n") { "Detail $it: punctuation, names, numbers 12.5 and 龍🦜." }
        val tags = listOf("important", "conversation")
        val parts = MemoryProposalSplitter.split(text, tags, reader)
        assertTrue(parts.size > 1)
        assertEquals(text, parts.joinToString("") { it.content })
        assertTrue(parts.all { it.tags == tags })
        assertTrue(parts.all { reader.measure(MemoryTokenBudget.Input(it.content, tags = it.tags)).fits })
        assertTrue(parts.none { it.content.lastOrNull()?.isHighSurrogate() == true })
        assertTrue(parts.none { it.content.firstOrNull()?.isLowSurrogate() == true })
    }

    @Test fun fittingProposalIsReturnedExactlyAsEntered() {
        val text = "  A short memory.\n"
        val parts = MemoryProposalSplitter.split(text, listOf("tag"), reader)
        assertEquals(listOf(MemoryProposalSplitter.Part(text, listOf("tag"))), parts)
    }

    @Test fun aVeryLongUnbrokenNameStillPreservesEveryCodePoint() {
        val text = "🦜".repeat(200)
        val parts = MemoryProposalSplitter.split(text, emptyList(), reader)
        assertEquals(text, parts.joinToString("") { it.content })
        assertTrue(parts.all { reader.measure(MemoryTokenBudget.Input(it.content)).fits })
    }

    @Test fun oversizedTagSetsArePartitionedWithoutLosingAnyLabelOrText() {
        val text = "Known detail."
        val tags = (1..8).map { "long-label-number-$it" }
        val parts = MemoryProposalSplitter.split(text, tags, reader)
        assertEquals(tags, parts.flatMap { it.tags }.distinct())
        for (tag in tags) assertEquals(text, parts.filter { tag in it.tags }.joinToString("") { it.content })
        assertTrue(parts.all { reader.measure(MemoryTokenBudget.Input(it.content, tags = it.tags)).fits })
    }

    @Test fun largeWhitespaceRunsAreRetained() {
        val text = "First detail." + " \n".repeat(200) + "Last detail."
        val parts = MemoryProposalSplitter.split(text, emptyList(), reader)
        assertEquals(text, parts.joinToString("") { it.content })
        assertTrue(parts.all { reader.measure(MemoryTokenBudget.Input(it.content, tags = it.tags)).fits })
    }
}
