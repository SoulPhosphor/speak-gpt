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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.RetrievableMemory
import kotlin.math.sqrt

class LongMessageSearchTest {

    /** Sections "about the subject" read as [onSubject]; a memory built with
     *  [cand] then has exactly its stated similarity to them. Every other
     *  section reads as [elsewhere], similarity 0 to every memory. */
    private val onSubject = floatArrayOf(1f, 0f, 0f)
    private val elsewhere = floatArrayOf(0f, 1f, 0f)

    private fun cand(id: String, similarity: Double?, content: String = "memory $id", importance: Int = 0) =
        LiveRetrieval.Candidate(
            RetrievableMemory(
                memoryId = id, scope = "global", content = content,
                embeddingText = null, importance = importance,
                createdAt = "2026-07-01T00:00:00Z", worldId = null
            ),
            similarity?.let { floatArrayOf(it.toFloat(), 0f, sqrt(1 - it * it).toFloat()) },
            emptyList(), emptyList()
        )

    private fun noise() = listOf(0.18, 0.20, 0.21, 0.22, 0.23, 0.24, 0.25, 0.26, 0.27, 0.28)
        .mapIndexed { i, s -> cand("noise$i", s, content = "unrelated note $i") }

    /** [count] filler words that match no memory, numbered so each is unique. */
    private fun filler(count: Int, from: Int = 0) = (from until from + count).joinToString(" ") { "zq$it" }

    /** A 300-word message whose only mention of the subject starts at word 200. */
    private fun longMessage(detail: String = "Pepper seems unwell lately") =
        filler(200) + " " + detail + " " + filler(96, from = 200)

    private fun read(
        message: String,
        index: LiveRetrieval.Index,
        settings: LongMessageSearch.Settings = LongMessageSearch.Settings(),
        now: () -> Long = { 0L },
        subject: String = "Pepper"
    ) = LongMessageSearch.read(message, index, settings, now) { text ->
        if (subject in text) onSubject else elsewhere
    }

    private fun words(text: String) = text.trim().split(Regex("\\s+"))

    /* ------------------------------ cutting ------------------------------ */

    @Test
    fun aMessageThatFitsInOneSectionIsNotCut() {
        assertTrue(LongMessageSearch.cut(filler(80)).isEmpty())
        assertTrue(LongMessageSearch.cut("Tell me about Pepper").isEmpty())
        assertTrue(LongMessageSearch.cut("").isEmpty())
    }

    @Test
    fun aShortMessageIsNeverReadInSections() {
        val index = LiveRetrieval.index(listOf(cand("pepper", 0.6)) + noise())
        var reads = 0
        val reading = LongMessageSearch.read(filler(80), index) { reads++; onSubject }
        assertSame(LongMessageSearch.Reading.NONE, reading)
        assertEquals(0, reads)
    }

    @Test
    fun everyWordIsInsideASectionAndSectionsStayWithinTheirSize() {
        val message = filler(333)
        val sections = LongMessageSearch.cut(message)
        assertEquals((1..sections.size).toList(), sections.map { it.number })
        assertTrue(sections.all { words(it.text).size <= 80 })
        assertEquals(words(message).toSet(), sections.flatMap { words(it.text) }.toSet())
        assertEquals("zq0", words(sections.first().text).first())
        assertEquals("zq332", words(sections.last().text).last())
    }

    @Test
    fun anyThoughtShorterThanHalfASectionIsWholeInsideOneSection() {
        val all = words(filler(333))
        for (message in listOf(filler(333), filler(333).replace(" zq100 ", "\nzq100 ").replace(" zq190 ", "\n\nzq190 "))) {
            val sections = LongMessageSearch.cut(message).map { words(it.text) }
            for (start in 0..all.size - 40) {
                val thought = all.subList(start, start + 40)
                assertTrue(
                    "words $start to ${start + 39} are split across sections",
                    sections.any { s -> s.indexOf(thought.first()).let { i -> i >= 0 && i + 40 <= s.size } }
                )
            }
        }
    }

    @Test
    fun periodsPlayNoPartInWhereAMessageIsCut() {
        val plain = (0 until 300).joinToString(" ") { "zq$it" }
        // The same words with periods dropped into the middle of thoughts.
        val dotted = (0 until 300).joinToString(" ") { if (it % 7 == 3) "zq$it." else "zq$it" }
        assertEquals(
            LongMessageSearch.cut(plain).map { words(it.text) },
            LongMessageSearch.cut(dotted).map { s -> words(s.text).map { it.trimEnd('.') } }
        )
    }

    @Test
    fun aSectionEndsAtALineBreakWhenOneIsClose() {
        // A paragraph break after word 69 (zq0..zq69), inside the last quarter
        // of the first section.
        val message = filler(70) + "\n\n" + filler(200, from = 70)
        val sections = LongMessageSearch.cut(message)
        assertEquals("zq69", words(sections[0].text).last())
        // The second section still starts at least half a section earlier.
        assertEquals("zq30", words(sections[1].text).first())
    }

    @Test
    fun theOpeningIsTheFirstFewWordsOfTheSection() {
        assertEquals("one two three", LongMessageSearch.Section(1, "one  two\nthree four").opening(3))
    }

    /* ------------------------------ reading ------------------------------ */

    @Test
    fun nothingIsReadWhenNoMemoryHasAVector() {
        val index = LiveRetrieval.index(listOf(cand("pepper", null, content = "Pepper is the user's cat.")))
        var reads = 0
        val reading = LongMessageSearch.read(longMessage(), index) { reads++; onSubject }
        assertSame(LongMessageSearch.Reading.NONE, reading)
        assertEquals(0, reads)
    }

    @Test
    fun sectionsWithTheRarestLibraryWordsAreReadFirstWhenThereAreTooMany() {
        val index = LiveRetrieval.index(listOf(cand("pepper", 0.6, content = "Pepper is the user's cat.")) + noise())
        // 600 words: 14 sections, more than the limit of 8. The name sits in
        // the last sections, which message order alone would never reach.
        val message = filler(560) + " Pepper seems unwell " + filler(37, from = 560)
        val sections = LongMessageSearch.cut(message)
        assertTrue(sections.size > 8)
        val withName = sections.filter { "Pepper" in it.text }.map { it.number }
        assertTrue(withName.all { it > 8 })
        assertEquals(withName.first(), LongMessageSearch.readingOrder(sections, index).first().number)

        val reading = read(message, index)
        assertEquals(8, reading.checked)
        assertTrue(reading.vectors.keys.containsAll(withName))
        assertTrue(reading.sectionLimitReached)
        assertFalse(reading.timedOut)
        val found = LongMessageSearch.matches(reading, index, LiveRetrieval.Params(), emptyList())
        assertEquals(listOf("pepper"), found.map { it.explanation.memory.memoryId })
    }

    @Test
    fun aMessageWithinTheSectionLimitReportsNoLimit() {
        val index = LiveRetrieval.index(listOf(cand("pepper", 0.6)) + noise())
        val reading = read(longMessage(), index)
        assertEquals(reading.sections.size, reading.checked)
        assertFalse(reading.sectionLimitReached)
        assertFalse(reading.timedOut)
    }

    @Test
    fun theTimeAllowanceStopsTheSearchAndReportsHowFarItGot() {
        val index = LiveRetrieval.index(listOf(cand("pepper", 0.6)) + noise())
        var clock = 0L
        // Each read takes 600 ms; the allowance is 1,500 ms.
        val reading = LongMessageSearch.read(longMessage(), index, now = { clock }) { clock += 600; elsewhere }
        assertTrue(reading.timedOut)
        assertFalse(reading.sectionLimitReached)
        assertEquals(3, reading.checked)
        assertEquals(3, reading.vectors.size)
        assertEquals(1800L, reading.millis)
        assertTrue(reading.sections.size > 3)
    }

    @Test
    fun aSectionTheModelCannotReadIsSkippedAndReported() {
        val index = LiveRetrieval.index(listOf(cand("pepper", 0.6, content = "Pepper is the user's cat.")) + noise())
        var reads = 0
        val reading = LongMessageSearch.read(longMessage(), index) { text ->
            if (++reads == 2) throw IllegalStateException("model busy")
            if ("Pepper" in text) onSubject else elsewhere
        }
        assertEquals(1, reading.failures.size)
        assertEquals("model busy", reading.failures.single().cause)
        assertTrue(RecallDiagnostics.sectionFailure(reading)!!.contains("(section ${reading.failures.single().section.number})"))
        assertEquals(reading.sections.size, reading.checked)
        assertEquals(reading.sections.size - 1, reading.vectors.size)
        assertFalse(reading.failures.single().section.number in reading.vectors.keys)
    }

    /* ------------------------------ matching ------------------------------ */

    private fun found(
        corpus: List<LiveRetrieval.Candidate>,
        message: String = longMessage(),
        whole: (LiveRetrieval.Index) -> List<LiveRetrieval.Explanation> = { emptyList() }
    ): List<LongMessageSearch.Match> {
        val index = LiveRetrieval.index(corpus)
        return LongMessageSearch.matches(read(message, index), index, LiveRetrieval.Params(), whole(index))
    }

    @Test
    fun aSmallDetailInALongMessageFindsItsMemory() {
        val corpus = listOf(cand("pepper", 0.55, content = "Pepper is the user's cat.")) + noise()
        // The whole message, read as one text, is not close to the memory.
        val whole = LiveRetrieval.search(longMessage(), elsewhere, corpus, LiveRetrieval.Params(), 5)
        assertTrue(whole.ranked.isEmpty())

        val match = found(corpus) { whole.explanations }.single()
        assertEquals("pepper", match.explanation.memory.memoryId)
        assertTrue("Pepper" in match.section.text)
        assertEquals(LongMessageSearch.cut(longMessage()).size, match.sectionCount)
        assertEquals(listOf("pepper"), match.explanation.matchedTerms)
        assertEquals("pepper", match.scored.memory.memoryId)
    }

    @Test
    fun aSectionOnlyLooselySimilarWithNoSharedWordFindsNothing() {
        // Stands out by meaning and clears the whole-message floor, but the
        // memory shares no informative word with the section.
        val corpus = listOf(cand("latch", 0.55, content = "The garden gate has a stiff latch.")) + noise()
        assertTrue(found(corpus).isEmpty())
    }

    @Test
    fun aSharedWordWithAMeaningMatchBelowTheSectionMinimumFindsNothing() {
        val below = listOf(cand("pepper", 0.47, content = "Pepper is the user's cat.")) + noise()
        assertTrue(found(below).isEmpty())
        val at = listOf(cand("pepper", 0.49, content = "Pepper is the user's cat.")) + noise()
        assertEquals(1, found(at).size)
    }

    @Test
    fun aSharedWordAloneIsNeverEnough() {
        // "It was about spring cleaning" shares "spring" with a memory about
        // moving house in spring and is not close to it in meaning.
        val corpus = listOf(cand("move", 0.20, content = "The user moved house in spring.")) + noise()
        val index = LiveRetrieval.index(corpus)
        val section = LongMessageSearch.Section(1, "spring")
        val reading = LongMessageSearch.Reading(
            listOf(section), mapOf(1 to onSubject), emptyList(), 1, false, false, 0
        )
        val e = LiveRetrieval.explain(section.text, onSubject, index, LiveRetrieval.Params())
            .single { it.memory.memoryId == "move" }
        assertTrue(e.passedKeyword)
        assertTrue(LongMessageSearch.matches(reading, index, LiveRetrieval.Params(), emptyList()).isEmpty())
    }

    @Test
    fun commonWordsDoNotCountAsSharedWords() {
        // The section and the memory share only filler words ("about",
        // "the", "user"), which keyword search drops.
        val corpus = listOf(cand("latch", 0.55, content = "About the user: the gate latch sticks.")) + noise()
        val message = filler(200) + " tell me about the user please " + filler(94, from = 200)
        val index = LiveRetrieval.index(corpus)
        val reading = read(message, index, subject = "about the user")
        assertTrue(LongMessageSearch.matches(reading, index, LiveRetrieval.Params(), emptyList()).isEmpty())
    }

    @Test
    fun wordFormsAreNormalizedBeforeComparing() {
        val corpus = listOf(cand("cats", 0.55, content = "The user fosters cats.")) + noise()
        val message = filler(200) + " the cat seems unwell " + filler(96, from = 200)
        val index = LiveRetrieval.index(corpus)
        val reading = read(message, index, subject = "the cat seems")
        val match = LongMessageSearch.matches(reading, index, LiveRetrieval.Params(), emptyList()).single()
        assertEquals(listOf("cat"), match.explanation.matchedTerms)
    }

    @Test
    fun aMemoryWithoutAVectorIsNotFoundBySection() {
        val corpus = listOf(cand("pepper", null, content = "Pepper is the user's cat.")) + noise()
        assertTrue(found(corpus).isEmpty())
    }

    @Test
    fun whatTheWholeMessageMatchedIsNotRepeated() {
        val corpus = listOf(cand("pepper", 0.55, content = "Pepper is the user's cat.")) + noise()
        val whole = LiveRetrieval.search(longMessage(), onSubject, corpus, LiveRetrieval.Params(), 5)
        assertEquals(listOf("pepper"), whole.ranked.map { it.memory.memoryId })
        assertTrue(found(corpus) { whole.explanations }.isEmpty())
    }

    @Test
    fun memoriesFoundBySectionAreOrderedBestFirst() {
        val corpus = listOf(
            cand("weaker", 0.50, content = "Pepper sleeps on the sofa."),
            cand("stronger", 0.60, content = "Pepper is the user's cat.")
        ) + noise()
        assertEquals(listOf("stronger", "weaker"), found(corpus).map { it.explanation.memory.memoryId })
    }

    @Test
    fun nothingReadFindsNothing() {
        val index = LiveRetrieval.index(listOf(cand("pepper", 0.6)) + noise())
        assertTrue(
            LongMessageSearch.matches(LongMessageSearch.Reading.NONE, index, LiveRetrieval.Params(), emptyList()).isEmpty()
        )
    }
    @Test
    fun repeatedIdenticalFailuresStopAndProduceOneAggregatedLog() {
        val index = LiveRetrieval.index(listOf(cand("pepper", .55)) + noise())
        var calls = 0
        val reading = LongMessageSearch.read(filler(700), index) {
            calls++
            throw IllegalStateException("broken session")
        }
        assertEquals(2, calls)
        assertEquals(2, reading.checked)
        assertTrue(reading.repeatedFailure)
        assertFalse(reading.sectionLimitReached)
        assertFalse(reading.timedOut)
        val log = RecallDiagnostics.sectionFailure(reading)!!
        assertTrue(log.contains("2 additional sections"))
        assertEquals(1, Regex("broken session").findAll(log).count())
        assertTrue(log.contains("sections 1, 2"))
    }

    @Test
    fun cooperativeDeadlineIsNotReportedAsEmbeddingFailure() {
        val index = LiveRetrieval.index(listOf(cand("pepper", .55)) + noise())
        val reading = LongMessageSearch.read(filler(200), index,
            budgetedEmbed = { _, remaining ->
                assertTrue(remaining > 0)
                throw EmbeddingQueue.DeadlineExceededException()
            }) { error("unbudgeted path") }
        assertTrue(reading.timedOut)
        assertTrue(reading.failures.isEmpty())
        assertEquals(1, reading.deadlineSection)
        assertEquals(1, reading.checked)
        assertFalse(reading.sectionLimitReached)
    }

    @Test
    fun cancellationIsPropagatedInsteadOfLoggedAsASectionFailure() {
        val index = LiveRetrieval.index(listOf(cand("pepper", .55)))
        org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
            LongMessageSearch.read(filler(200), index) { throw java.util.concurrent.CancellationException() }
        }
    }

    @Test
    fun excerptBoundsPathologicalDictationAndRetainsSectionNumber() {
        val section = LongMessageSearch.Section(7, "x".repeat(500) + " next words")
        assertEquals(96, section.opening().length)
        assertEquals(7, section.number)
        val unicode = LongMessageSearch.Section(2, "a" + "🐺".repeat(100)).opening()
        assertEquals(96, unicode.codePointCount(0, unicode.length))
        assertFalse(unicode.last().isHighSurrogate())
    }

    @Test
    fun boundedPrioritizationPreservesTheFullOrdersPrefix() {
        val index = LiveRetrieval.index(listOf(cand("pepper", .55, "Pepper is a cat")) + noise())
        val message = filler(1960) + " Pepper seems unwell " + filler(37, 1960)
        val sections = LongMessageSearch.cut(message)
        assertTrue(sections.size > 8)
        assertEquals(LongMessageSearch.readingOrder(sections, index).take(8),
            LongMessageSearch.readingOrder(sections, index, 8))
        assertTrue(LongMessageSearch.readingOrder(sections, index, 0).isEmpty())
    }

}
