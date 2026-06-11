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

package org.teslasoft.assistant.preferences.lorebook

/**
 * Decides whether a trigger phrase fires on a user message.
 *
 * Default mode: case-insensitive whole-word matching with light suffix
 * folding, so "dragon" fires on "dragons"/"dragon's" and "casting" fires on
 * "cast" — but "cat" no longer fires on "catalog" the way the old raw
 * substring match did. Multi-word triggers must appear as consecutive words
 * (punctuation between them is fine).
 *
 * Escape hatch: a trigger wrapped in double quotes ("dragon fire") demands
 * that exact text (still case-insensitive, substring) with no word folding —
 * for sub-word matches or when the folding is unwanted.
 *
 * The folding is deliberately a few hand-rolled English suffix rules, not an
 * NLP lemmatizer: lorebook triggers are mostly names and invented words,
 * which real lemmatizers mangle, and a library would add weight for no gain.
 * Each word expands to a small set of stem candidates and two words match
 * when their candidate sets intersect — expanding *both* sides is what lets
 * "running" (→ run) meet "run" without a full stemming algorithm.
 *
 * Pure Kotlin (no Android imports) so it is unit-testable on the JVM.
 */
object LoreBookTriggerMatcher {

    private val QUOTE_CHARS = charArrayOf('"', '“', '”', '„')
    private val APOSTROPHES = charArrayOf('\'', '’')
    private const val VOWELS = "aeiou"

    fun matches(message: String, trigger: String): Boolean {
        val t = trigger.trim()
        if (t.isEmpty() || message.isBlank()) return false

        val exact = exactPhrase(t)
        if (exact != null) {
            return exact.isNotEmpty() && message.lowercase().contains(exact.lowercase())
        }

        val triggerWords = tokenize(t)
        if (triggerWords.isEmpty()) return false
        val messageWords = tokenize(message)
        if (messageWords.size < triggerWords.size) return false

        val triggerStems = triggerWords.map { stemCandidates(it) }
        val messageStems = messageWords.map { stemCandidates(it) }

        for (start in 0..(messageStems.size - triggerStems.size)) {
            var allMatch = true
            for (i in triggerStems.indices) {
                if (messageStems[start + i].none { it in triggerStems[i] }) {
                    allMatch = false
                    break
                }
            }
            if (allMatch) return true
        }
        return false
    }

    /** Inner text when the whole trigger is wrapped in double quotes
     *  (straight or curly), else null. Inner spacing is preserved — quoting
     *  "dragon " with a trailing space is a legitimate way to exclude
     *  "dragons", so exact must mean exact. */
    private fun exactPhrase(trigger: String): String? {
        if (trigger.length < 2) return null
        if (trigger.first() !in QUOTE_CHARS || trigger.last() !in QUOTE_CHARS) return null
        return trigger.substring(1, trigger.length - 1)
    }

    private fun tokenize(text: String): List<String> {
        // Apostrophes stay inside tokens so possessives ("dragon's") survive
        // as one word for the candidate expansion to strip.
        return text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}'’]+"))
            .map { it.trim(*APOSTROPHES) }
            .filter { it.isNotEmpty() }
    }

    /**
     * The word plus its plausible stems. Length guards keep short words intact
     * ("as", "red", "ring" stay themselves) — a wrong fold on a short word is
     * far more likely to create a false positive than a long one.
     */
    private fun stemCandidates(rawWord: String): Set<String> {
        var word = rawWord
        for (apostrophe in APOSTROPHES) {
            if (word.endsWith("${apostrophe}s")) {
                word = word.dropLast(2)
                break
            }
        }

        val candidates = HashSet<String>()
        candidates.add(word)

        if (word.endsWith("ies") && word.length > 4) {
            candidates.add(word.dropLast(3) + "y") // stories → story
        }
        if (word.endsWith("es") && word.length > 4) {
            candidates.add(word.dropLast(2)) // boxes → box
        }
        if (word.endsWith("s") && !word.endsWith("ss") && word.length > 3) {
            candidates.add(word.dropLast(1)) // dragons → dragon
        }
        if (word.endsWith("ing") && word.length > 5) {
            val stem = word.dropLast(3)
            candidates.add(stem)         // casting → cast
            candidates.add(stem + "e")   // making → make
            addUndoubled(candidates, stem) // running → run
        }
        if (word.endsWith("ed") && word.length > 4) {
            val stem = word.dropLast(2)
            candidates.add(stem)         // walked → walk
            candidates.add(stem + "e")   // saved → save
            addUndoubled(candidates, stem) // planned → plan
        }
        return candidates
    }

    /** After stripping -ing/-ed, fold a doubled final consonant (runn → run). */
    private fun addUndoubled(candidates: HashSet<String>, stem: String) {
        if (stem.length > 3 &&
            stem[stem.length - 1] == stem[stem.length - 2] &&
            stem.last() !in VOWELS
        ) {
            candidates.add(stem.dropLast(1))
        }
    }
}
