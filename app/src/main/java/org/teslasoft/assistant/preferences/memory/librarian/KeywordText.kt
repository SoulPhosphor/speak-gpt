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

/**
 * Turns text into keyword-search terms for [LiveRetrieval]: whole Unicode
 * words, lowercased, with common words dropped and simple English word-form
 * endings removed so "crashing" matches "crash" and "cats" matches "cat".
 * Matching is always on whole terms — "cat" never matches inside "catalog".
 * Pure Kotlin, unit tested in LiveRetrievalTest.
 */
object KeywordText {

    private val WORD = Regex("[\\p{L}\\p{Nd}']+")

    /**
     * Words that never count as informative. Without this list, a common word
     * that happens to appear in only one memory would look rare in a small
     * library and pull that memory in. "user" is included because memories
     * are commonly written "The user ...".
     */
    private val COMMON = setOf(
        "a", "about", "above", "after", "again", "against", "all", "also", "am", "an", "and",
        "any", "are", "aren't", "as", "at", "be", "because", "been", "before", "being", "below",
        "between", "both", "but", "by", "can", "can't", "cannot", "could", "couldn't", "did",
        "didn't", "do", "does", "doesn't", "doing", "don't", "down", "during", "each", "few",
        "for", "from", "further", "get", "got", "go", "going", "had", "hadn't", "has", "hasn't",
        "have", "haven't", "having", "he", "he'd", "he'll", "he's", "hello", "her", "here",
        "here's", "hers", "herself", "hey", "hi", "him", "himself", "his", "how", "how's", "i",
        "i'd", "i'll", "i'm", "i've", "if", "in", "into", "is", "isn't", "it", "it's", "its",
        "itself", "just", "know", "let's", "like", "lot", "many", "me", "more", "most", "much",
        "mustn't", "my", "myself", "no", "nor", "not", "now", "of", "off", "ok", "okay", "on",
        "once", "only", "or", "other", "ought", "our", "ours", "ourselves", "out", "over", "own",
        "please", "really", "said", "same", "say", "shan't", "she", "she'd", "she'll", "she's",
        "should", "shouldn't", "so", "some", "still", "stuff", "such", "tell", "than", "thank",
        "thanks", "that", "that's", "the", "their", "theirs", "them", "themselves", "then",
        "there", "there's", "these", "they", "they'd", "they'll", "they're", "they've", "thing",
        "things", "think", "this", "those", "through", "to", "today", "too", "under", "until",
        "up", "user", "very", "want", "was", "wasn't", "we", "we'd", "we'll", "we're", "we've",
        "well", "were", "weren't", "what", "what's", "when", "when's", "where", "where's",
        "which", "while", "who", "who's", "whom", "why", "why's", "with", "won't", "would",
        "wouldn't", "yeah", "yes", "you", "you'd", "you'll", "you're", "you've", "your", "yours",
        "yourself", "yourselves"
    )

    /** The informative, normalized terms of [text], in order (may repeat). */
    fun terms(text: String): List<String> =
        WORD.findAll(text.lowercase().replace('’', '\''))
            .map { it.value.trim('\'') }
            .filter { it.length >= 2 && it !in COMMON }
            .map(::stem)
            .filter { it.length >= 2 }
            .toList()

    /** Removes common English word-form endings. Deliberately simple: the
     *  same rule runs on both the message and the memory, so it only has to
     *  be consistent, not linguistically exact. */
    fun stem(word: String): String {
        var w = word
        if (w.endsWith("'s")) w = w.dropLast(2)
        return when {
            w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
            w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
            w.length > 4 && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("sses") ||
                w.endsWith("xes") || w.endsWith("zes")) -> w.dropLast(2)
            w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
            w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
            else -> w
        }
    }
}
