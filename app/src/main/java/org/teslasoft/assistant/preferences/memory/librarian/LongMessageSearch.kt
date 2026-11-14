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

import org.teslasoft.assistant.preferences.memory.ScoredMemory

/**
 * Finds the saved memory for a small detail inside a long message
 * (conversation_recall_plan.md G.10). Pure Kotlin, unit tested in
 * LongMessageSearchTest and measured on the saved-memory test set.
 *
 * The whole message is always searched first, by [LiveRetrieval], exactly as
 * before. A message longer than one section is then also searched in
 * overlapping sections:
 *  - a section is cut at a line break where one is close, otherwise between
 *    words; periods play no part, because dictated text cannot be trusted to
 *    have them in the right places;
 *  - each section starts at least half a section before the previous one
 *    ends, so any thought shorter than half a section is whole inside at
 *    least one section;
 *  - a section is only used to FIND a memory. What is sent is the whole
 *    memory; a section's text never reaches the AI.
 *
 * A section's match counts only when meaning search and keyword search
 * agree (owner decision for Step 2, measured on the saved-memory test set):
 *  - the memory passes the section's relevance gate, by meaning or by
 *    keywords, the same gate a whole message uses; and
 *  - the memory's similarity to the section is at least
 *    [Settings.sectionSimilarityMin]; and
 *  - the section and the memory share at least one informative word (common
 *    words are dropped and word forms normalized by [KeywordText]).
 * A section that is only loosely similar to a memory, with no word in
 * common, sent many wrong memories on the test set. The cost of this rule is
 * that a section describing a memory in entirely different words does not
 * find it. Memories found only through a section are placed after everything
 * the whole message matched.
 */
object LongMessageSearch {

    /** The engineering numbers of the section search. */
    data class Settings(
        /** Words in one section. A message of at most this many words is
         *  searched whole only. */
        val sectionWords: Int = 80,
        /** Most sections read for one message. When a message has more, the
         *  sections holding the rarest library words are read first. */
        val maxSections: Int = 8,
        /** Time the section search may add to sending one message. Checked
         *  before each read, so one read can finish past it. Not yet
         *  measured on a phone. */
        val timeAllowanceMillis: Long = 1_500,
        /** Similarity a memory must reach against a section before the
         *  section's match counts. Provisional: chosen on the saved-memory
         *  test set (memory baseline.md), not validated on other libraries. */
        val sectionSimilarityMin: Double = 0.48
    )

    /** One section of a message. [number] counts from 1 in message order. */
    data class Section(val number: Int, val text: String) {
        /** The first few words, for telling the user which section it was. */
        fun opening(words: Int = 6): String =
            WORD.findAll(text).take(words).joinToString(" ") { it.value }
    }

    /** A section the embedding model could not read. */
    data class Failure(val section: Section, val cause: String)

    /**
     * What reading one message's sections produced. Made once per message;
     * saved-memory search and anything else that searches by section reuse
     * it instead of reading the message again.
     */
    data class Reading(
        /** Every section of the message, in message order. Empty when the
         *  message fits in one section or the section search did not apply. */
        val sections: List<Section>,
        /** The vectors of the sections that were read, by section number. */
        val vectors: Map<Int, FloatArray>,
        val failures: List<Failure>,
        /** Sections the search got to: read, or tried and failed. */
        val checked: Int,
        /** True when the time allowance ended the search early. */
        val timedOut: Boolean,
        /** True when the message has more sections than one search reads
         *  and the time allowance did not end the search first. */
        val sectionLimitReached: Boolean,
        /** Time spent on the section reads, waiting for the model included. */
        val millis: Long
    ) {
        companion object {
            val NONE = Reading(emptyList(), emptyMap(), emptyList(), 0, false, false, 0)
        }
    }

    /** A memory found through a section and not by the whole message. */
    data class Match(
        val section: Section,
        /** How many sections the message has. */
        val sectionCount: Int,
        /** The memory's numbers against that section. */
        val explanation: LiveRetrieval.Explanation
    ) {
        val scored: ScoredMemory
            get() = ScoredMemory(
                explanation.memory,
                (explanation.similarity ?: 0.0).toFloat(),
                explanation.score.toFloat()
            )
    }

    private val WORD = Regex("\\S+")

    /**
     * Cuts [message] into overlapping sections of about [sectionWords]
     * words. Returns nothing when the message fits in one section.
     */
    fun cut(message: String, sectionWords: Int = Settings().sectionWords): List<Section> {
        val size = sectionWords.coerceAtLeast(4)
        val words = WORD.findAll(message).toList()
        val n = words.size
        if (n <= size) return emptyList()
        // A section may begin at word i "on a line break" when a new line
        // separates it from the word before.
        val afterBreak = BooleanArray(n) { i ->
            i > 0 && message.substring(words[i - 1].range.last + 1, words[i].range.first)
                .any { it == '\n' || it == '\r' }
        }
        fun lastBreak(from: Int, to: Int): Int? =
            (minOf(to, n - 1) downTo from).firstOrNull { afterBreak[it] }

        val half = size / 2
        val quarter = size / 4
        val sections = ArrayList<Section>()
        var start = 0
        while (true) {
            var end = minOf(start + size, n)
            // Prefer to end on a line break in the last quarter of the section.
            if (end < n) end = lastBreak(start + size - quarter, end) ?: end
            sections.add(
                Section(
                    sections.size + 1,
                    message.substring(words[start].range.first, words[end - 1].range.last + 1)
                )
            )
            if (end >= n) break
            // The next section starts no later than half a section before
            // this one ends, on a line break when one is in reach.
            val latest = end - half
            val next = lastBreak(start + quarter.coerceAtLeast(1), latest) ?: latest
            start = next.coerceAtLeast(start + 1)
        }
        return sections
    }

    /**
     * The order in which sections are read: those holding the rarest library
     * words (names and unusual terms) first, each next pick favouring words
     * no earlier pick already covers; sections with nothing left to add
     * follow in message order.
     */
    fun readingOrder(sections: List<Section>, index: LiveRetrieval.Index): List<Section> {
        val known = sections.associate { s ->
            s.number to LiveRetrieval.queryTerms(s.text).mapNotNull { t -> index.rarity(t)?.let { t to it } }.toMap()
        }
        val remaining = sections.toMutableList()
        val covered = HashSet<String>()
        val order = ArrayList<Section>(sections.size)
        while (remaining.isNotEmpty()) {
            var best: Section? = null
            var bestGain = 0.0
            for (s in remaining) {
                val gain = known.getValue(s.number).entries.sumOf { (t, w) -> if (t in covered) 0.0 else w }
                if (gain > bestGain + 1e-12) {
                    best = s
                    bestGain = gain
                }
            }
            if (best == null) break
            order.add(best)
            remaining.remove(best)
            covered.addAll(known.getValue(best.number).keys)
        }
        order.addAll(remaining)
        return order
    }

    /**
     * Reads the sections of [message] with [embed], within the section limit
     * and the time allowance. Nothing is read when the message fits in one
     * section or no memory in [index] has a vector to compare with. A
     * section [embed] cannot read is skipped and reported in the result.
     */
    fun read(
        message: String,
        index: LiveRetrieval.Index,
        settings: Settings = Settings(),
        now: () -> Long = { System.nanoTime() / 1_000_000 },
        embed: (String) -> FloatArray
    ): Reading {
        if (index.withVector == 0) return Reading.NONE
        val sections = cut(message, settings.sectionWords)
        if (sections.isEmpty()) return Reading.NONE
        val planned = readingOrder(sections, index).take(settings.maxSections.coerceAtLeast(0))
        val vectors = LinkedHashMap<Int, FloatArray>()
        val failures = ArrayList<Failure>()
        var timedOut = false
        var checked = 0
        val started = now()
        for (section in planned) {
            if (now() - started >= settings.timeAllowanceMillis) {
                timedOut = true
                break
            }
            checked++
            try {
                vectors[section.number] = embed(section.text)
            } catch (t: Throwable) {
                failures.add(Failure(section, t.message ?: t.javaClass.simpleName))
            }
        }
        return Reading(
            sections, vectors, failures, checked, timedOut,
            sectionLimitReached = !timedOut && planned.size < sections.size,
            millis = now() - started
        )
    }

    /**
     * The memories [reading]'s sections found that the whole message did not,
     * best first. [wholeMessage] is the whole-message search's explanations;
     * a memory that already passed there keeps its place and is not repeated.
     */
    fun matches(
        reading: Reading,
        index: LiveRetrieval.Index,
        params: LiveRetrieval.Params,
        wholeMessage: List<LiveRetrieval.Explanation>,
        useImportanceRatings: Boolean = true,
        settings: Settings = Settings()
    ): List<Match> {
        if (reading.vectors.isEmpty()) return emptyList()
        val alreadyMatched = wholeMessage.filter { it.passed }.map { it.memory.memoryId }.toSet()
        val byNumber = reading.sections.associateBy { it.number }
        val best = LinkedHashMap<String, Match>()
        for ((number, vector) in reading.vectors.entries.sortedBy { it.key }) {
            val section = byNumber[number] ?: continue
            for (e in LiveRetrieval.explain(section.text, vector, index, params, useImportanceRatings)) {
                val id = e.memory.memoryId
                if (id in alreadyMatched) continue
                val counts = e.passed && e.matchedTerms.isNotEmpty() &&
                    (e.similarity ?: 0.0) >= settings.sectionSimilarityMin
                if (!counts) continue
                val previous = best[id]
                if (previous == null || e.score > previous.explanation.score) {
                    best[id] = Match(section, reading.sections.size, e)
                }
            }
        }
        return best.values.sortedByDescending { it.explanation.score }
    }
}
