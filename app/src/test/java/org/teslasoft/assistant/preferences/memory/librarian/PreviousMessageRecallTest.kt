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

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.ImportanceRanking
import org.teslasoft.assistant.preferences.memory.RetrievableMemory
import org.teslasoft.assistant.preferences.memory.RetrievalPolicy
import org.teslasoft.assistant.preferences.memory.enforcer.RetrievalBackfill
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * Scores the saved-memory test set's nine conversations with
 * [PreviousMessageSearch] at 0, 1, 2 and 3 previous messages
 * (conversation_recall_plan.md Step 3). Each conversation is its own chat, so
 * its first turn has no previous message.
 *
 * Every turn goes the way a sent message goes: the search hands on its
 * candidates (Maximum Memories + the enforcer's scan margin + 1), and the
 * enforcer's own selection ([RetrievalBackfill.select]) applies Maximum
 * Memories and the +3 rule. The test set has no lore and no memory too large
 * for the memory space, so no candidate is filtered out here; those filters
 * are exercised in PreviousMessageSearchTest and PlusThreeSelectionRuleTest.
 *
 * Two sets of inputs recorded before this step are scored. Neither is a new
 * run of the model, and nothing here runs on Android:
 *  - "android_ort120_scores": the similarities the real model produced on
 *    Android ONNX Runtime 1.20, replayed as in NativeMemoryGateRegressionTest;
 *  - "reference_vectors": the frozen full vectors in vectors.json.
 *
 * Results are checked against the test set's expected answers (cases.json,
 * never changed here), and the exact memories sent for every turn are also
 * recorded in previous_message_results.json so any change is seen.
 */
class PreviousMessageRecallTest {

    private val params = LiveRetrieval.Params()
    private val defaultMaximum = RetrievalPolicy.DEFAULT_TOP_K
    private val maximums = listOf(1, 2, 3, 5, defaultMaximum)
    private val counts = 0..PreviousMessageSearch.MAX_PREVIOUS_MESSAGES
    private val sources = listOf("android_ort120_scores", "reference_vectors")

    private fun resource(name: String): String = checkNotNull(
        javaClass.classLoader!!.getResourceAsStream("recall_test_set/$name")
    ) { "Missing recall fixture: $name" }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private class Turn(val id: String, val text: String, val expect: Set<String>)

    private val casesText = resource("cases.json")
    private val cases = JSONObject(casesText)
    private val memories = cases.getJSONArray("memories").objects()
    private val conversations: List<List<Turn>> = cases.getJSONArray("conversations").objects().map { c ->
        c.getJSONArray("messages").objects().map {
            Turn(it.getString("id"), it.getString("text"), it.getJSONArray("expect").strings().toSet())
        }
    }

    private fun memory(json: JSONObject) = RetrievableMemory(
        memoryId = json.getString("id"), scope = "global", content = json.getString("content"),
        embeddingText = null, importance = 0, createdAt = "2028-01-01T00:00:00Z", worldId = null
    )

    private fun own(text: String, vector: FloatArray, index: LiveRetrieval.Index, limit: Int) =
        SavedMemorySearch.search(text, vector, index, params, limit, true) { LongMessageSearch.Reading.NONE }

    /** The candidates for a turn searched with its [count] nearest previous turns. */
    private fun interface Source {
        fun candidates(turns: List<Turn>, position: Int, count: Int, candidateLimit: Int): PreviousMessageSearch.Result
    }

    /** The frozen reference vectors: one library, as the app holds it. */
    private fun referenceVectors(): Source {
        val vectors = JSONObject(resource("vectors.json"))
        fun vector(id: String): FloatArray {
            val array = vectors.getJSONArray(id)
            return FloatArray(array.length()) { array.getDouble(it).toFloat() }
        }
        val index = LiveRetrieval.index(memories.map {
            LiveRetrieval.Candidate(memory(it), vector(it.getString("id")), it.getJSONArray("tags").strings(), emptyList())
        })
        return Source { turns, position, count, candidateLimit ->
            val current = turns[position]
            val previous = turns.subList(0, position).reversed()
            val result = PreviousMessageSearch.search(
                PreviousMessageSearch.Message.withVector(current.text, vector(current.id)),
                previous.map { PreviousMessageSearch.Message.withVector(it.text, vector(it.id)) },
                count, index, params, candidateLimit, true
            )
            // The same answer when the searches are run first and combined after.
            val combined = PreviousMessageSearch.combine(
                own(current.text, vector(current.id), index, candidateLimit),
                previous.take(count).map { own(it.text, vector(it.id), index, index.corpus.size) },
                candidateLimit, true
            )
            assertEquals("${current.id} at $count", result.found, combined.found)
            assertTrue("${current.id}: every message had its vector", result.searchedByKeywordsOnly.isEmpty())
            result
        }
    }

    /**
     * The similarities measured on Android. Only each message's similarity
     * to each memory was recorded, so every message is searched against
     * two-dimensional vectors that reproduce exactly its measured numbers,
     * and the finished searches are combined.
     */
    private fun androidScores(): Source {
        val measured = JSONObject(resource("android_ort120_similarity_scores.json"))
        assertEquals(hash(casesText), measured.getString("cases_sha256"))
        assertEquals(memories.map { it.getString("id") }, measured.getJSONArray("memory_ids").strings())
        val query = floatArrayOf(1f, 0f)
        fun index(id: String): LiveRetrieval.Index {
            val scores = measured.getJSONObject("messages").getJSONArray(id)
            assertEquals(memories.size, scores.length())
            return LiveRetrieval.index(memories.mapIndexed { i, m ->
                val similarity = scores.getDouble(i)
                LiveRetrieval.Candidate(
                    memory(m), floatArrayOf(similarity.toFloat(), sqrt(1.0 - similarity * similarity).toFloat()),
                    m.getJSONArray("tags").strings(), emptyList()
                )
            })
        }
        return Source { turns, position, count, candidateLimit ->
            val current = turns[position]
            PreviousMessageSearch.combine(
                own(current.text, query, index(current.id), candidateLimit),
                turns.subList(0, position).reversed().take(count).map {
                    own(it.text, query, index(it.id), memories.size)
                },
                candidateLimit, true
            )
        }
    }

    private fun source(name: String) = if (name == "reference_vectors") referenceVectors() else androidScores()

    /** One sent memory: its id and how many messages back it was found. */
    private class Sent(val id: String, val messagesBack: Int)

    /** A turn's candidates, then the enforcer's selection at [maximum]. */
    private fun send(source: Source, turns: List<Turn>, position: Int, count: Int, maximum: Int): List<Sent> {
        val result = source.candidates(turns, position, count, RetrievalBackfill.scanCap(maximum) + 1)
        val back = result.found.associate { it.scored.memory.memoryId to it.messagesBack }
        return RetrievalBackfill.select(
            result.ranked, maximum, RetrievalBackfill.scanCap(maximum),
            isMandatory = { ImportanceRanking.isMandatory(it.memory.importance.toDouble(), true) }
        ) { true }.kept.map { Sent(it.memory.memoryId, back.getValue(it.memory.memoryId)) }
    }

    private data class Tally(
        /** Expected memories sent, over all 30 turns. */
        val correct: Int,
        /** Expected memories not sent. */
        val missed: Int,
        /** Every memory sent that the turn did not expect. */
        val incorrect: Int,
        val firstFollowUpsKept: Int,
        val laterFollowUpsKept: Int,
        val newSubjectsFound: Int,
        /** Of [incorrect]: the old subject's memory, sent after the change. */
        val oldSubjectAfterChange: Int
    ) {
        override fun toString() = "correct=$correct missed=$missed incorrect=$incorrect " +
            "first_follow_ups_kept=$firstFollowUpsKept/9 later_follow_ups_kept=$laterFollowUpsKept/3 " +
            "new_subjects_found=$newSubjectsFound/9 old_subject_after_change=$oldSubjectAfterChange " +
            "other_unexpected=${incorrect - oldSubjectAfterChange}"
    }

    private fun score(source: Source, count: Int, maximum: Int, record: JSONObject? = null): Tally {
        var correct = 0
        var missed = 0
        var incorrect = 0
        var first = 0
        var later = 0
        var newSubjects = 0
        var oldSubject = 0
        for (turns in conversations) {
            for (position in turns.indices) {
                val turn = turns[position]
                val sent = send(source, turns, position, count, maximum)
                val ids = sent.map { it.id }
                val alone = send(source, turns, position, 0, maximum).map { it.id }

                // What the step must guarantee, whatever the numbers are.
                assertEquals("${turn.id}: listed twice", ids.size, ids.toSet().size)
                assertEquals("${turn.id}: the new message's own result must come first, unchanged",
                    alone, ids.take(alone.size))
                assertEquals("${turn.id}: older matches must follow newer ones",
                    sent.map { it.messagesBack }.sorted(), sent.map { it.messagesBack })
                assertTrue("${turn.id}: nothing is carried further than the count",
                    sent.all { it.messagesBack <= minOf(count, position) })
                assertTrue("${turn.id}: Maximum Memories (no memory here is rated +3)", ids.size <= maximum)
                for (carried in sent.filter { it.messagesBack > 0 }) {
                    // Passed the gate for that earlier message, on its own.
                    val earlier = source.candidates(turns, position - carried.messagesBack, 0, memories.size)
                    assertTrue("${turn.id}: ${carried.id} did not pass for the earlier message",
                        carried.id in earlier.ranked.map { it.memory.memoryId })
                }

                val kept = ids.containsAll(turn.expect)
                when {
                    position == 0 -> Unit
                    position == turns.lastIndex -> if (kept) newSubjects++
                    position == 1 -> if (kept) first++
                    else -> if (kept) later++
                }
                correct += ids.count { it in turn.expect }
                missed += turn.expect.count { it !in ids }
                incorrect += ids.count { it !in turn.expect }
                if (position == turns.lastIndex) oldSubject += ids.count { it in turns.first().expect && it !in turn.expect }
                record?.put(turn.id, JSONArray(sent.map { "${it.id}@${it.messagesBack}" }))
            }
        }
        return Tally(correct, missed, incorrect, first, later, newSubjects, oldSubject)
    }

    @Test
    fun theTestSetHasTheConversationsTheseResultsDescribe() {
        assertEquals(listOf(3, 3, 3, 3, 3, 3, 4, 4, 4), conversations.map { it.size })
        assertEquals(30, conversations.sumOf { it.size })
        assertTrue("Every turn expects exactly one memory", conversations.flatten().all { it.expect.size == 1 })
        assertTrue("No test memory is rated", memories.none { it.has("importance") })
        // The last turn changes the subject; every turn before it shares one.
        for (turns in conversations) {
            assertTrue(turns.dropLast(1).all { it.expect == turns.first().expect })
            assertTrue(turns.last().expect != turns.first().expect)
        }
    }

    @Test
    fun withNoPreviousMessagesEveryTurnIsExactlyTheExistingSearchAndSelection() {
        for (name in sources) {
            val source = source(name)
            for (maximum in maximums) for (turns in conversations) for (position in turns.indices) {
                val limit = RetrievalBackfill.scanCap(maximum) + 1
                val result = source.candidates(turns, position, 0, limit)
                assertEquals(result.current.ranked, result.ranked)
                assertTrue(result.previous.isEmpty())
                // Offering previous messages changes nothing while the count is 0.
                assertEquals(result.ranked.map { it.memory.memoryId },
                    source.candidates(turns, position, 0, limit).current.ranked.map { it.memory.memoryId })
            }
        }
    }

    /**
     * Checked against the expected answers at the default Maximum Memories.
     * These are requirements, not a record of whatever the search returns.
     */
    @Test
    fun followUpsKeepTheirExpectedMemoryAndNothingExpectedIsLost() {
        for (name in sources) {
            val source = source(name)
            val alone = score(source, 0, defaultMaximum)
            // Searched alone, two follow-ups and one first message miss their
            // memory: the starting point this step is measured against.
            assertEquals("$name alone", 27, alone.correct)
            assertEquals("$name alone", 3, alone.missed)
            for (count in 1..PreviousMessageSearch.MAX_PREVIOUS_MESSAGES) {
                val t = score(source, count, defaultMaximum)
                assertEquals("$name at $count: every first follow-up keeps its memory", 9, t.firstFollowUpsKept)
                assertEquals("$name at $count: every second follow-up keeps its memory", 3, t.laterFollowUpsKept)
                assertEquals("$name at $count: every new subject finds its own memory", 9, t.newSubjectsFound)
                // The one remaining miss is a first message, which has
                // nothing before it.
                assertEquals("$name at $count", 29, t.correct)
                assertEquals("$name at $count", 1, t.missed)
            }
            // An expected memory found at one count is found at every higher one.
            for (turns in conversations) for (position in turns.indices) {
                var found = emptySet<String>()
                for (count in counts) {
                    val now = send(source, turns, position, count, defaultMaximum).map { it.id }
                        .filter { it in turns[position].expect }.toSet()
                    assertTrue("${turns[position].id} lost an expected memory at $count", now.containsAll(found))
                    found = now
                }
            }
        }
    }

    /**
     * The measured cost, counted against the expected answers: memories sent
     * that the turn did not expect. These numbers are what was measured, kept
     * exact so that a change in either direction is seen and reported.
     */
    @Test
    fun unexpectedMemoriesCarriedFromEarlierMessagesAreCounted() {
        // source -> for 0, 1, 2, 3 previous messages: incorrect, of which old subject.
        val measured = mapOf(
            "android_ort120_scores" to listOf(9 to 0, 25 to 8, 31 to 9, 31 to 9),
            "reference_vectors" to listOf(10 to 0, 27 to 8, 33 to 9, 33 to 9)
        )
        for (name in sources) {
            val source = source(name)
            for (count in counts) {
                val t = score(source, count, defaultMaximum)
                assertEquals("$name at $count: incorrect", measured.getValue(name)[count].first, t.incorrect)
                assertEquals("$name at $count: old subject", measured.getValue(name)[count].second, t.oldSubjectAfterChange)
            }
        }
    }

    /**
     * Maximum Memories changes the result, because a carried memory comes
     * after everything the new message matched, right or wrong.
     */
    @Test
    fun aLowMaximumMemoriesDropsCarriedMemoriesFirst() {
        val source = androidScores()
        val turns = conversations[6]
        assertEquals("conversation_07_turn_2", turns[1].id)
        // "Where should I take him?" matches the wrong memory on its own. The
        // right one is carried from the turn before, in second place.
        assertEquals(listOf("hobby_camera"), send(source, turns, 1, 1, 1).map { it.id })
        assertEquals(listOf("hobby_camera", "pet_brindle"), send(source, turns, 1, 1, 2).map { it.id })
        assertEquals(listOf("hobby_camera", "pet_brindle"), send(source, turns, 1, 1, defaultMaximum).map { it.id })
        // Fewer follow-ups are kept at Maximum Memories 1 than at the default.
        assertTrue(score(source, 1, 1).firstFollowUpsKept < score(source, 1, defaultMaximum).firstFollowUpsKept)
        // From 5 upward nothing differs on this set: no turn has more than
        // five candidates at any count.
        for (count in counts) assertEquals(score(source, count, 5), score(source, count, defaultMaximum))
    }

    @Test
    fun everyTurnAtEveryCountAndMaximumMatchesTheRecord() {
        val measured = JSONObject().put("cases_sha256", hash(casesText))
        for (name in sources) {
            val source = source(name)
            val byMaximum = JSONObject()
            for (maximum in maximums) {
                val byCount = JSONObject()
                for (count in counts) {
                    val record = JSONObject()
                    val t = score(source, count, maximum, record)
                    byCount.put(count.toString(), record)
                    println("PREVIOUS $name maximum=$maximum count=$count $t")
                }
                byMaximum.put(maximum.toString(), byCount)
            }
            measured.put(name, byMaximum)
        }
        println("PREVIOUS RECORD $measured")

        // Results are printed before this read. Record measured results by
        // hand; never invent a passing record here.
        val stream = javaClass.classLoader!!.getResourceAsStream("recall_test_set/previous_message_results.json")
        checkNotNull(stream) { "The results are not recorded yet; record the printed PREVIOUS RECORD line." }
        val recorded = JSONObject(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        assertEquals("Expected answers must stay fixed", recorded.getString("cases_sha256"), hash(casesText))
        for (name in sources) for (maximum in maximums) for (count in counts) {
            val was = recorded.getJSONObject(name).getJSONObject(maximum.toString()).getJSONObject(count.toString())
            val now = measured.getJSONObject(name).getJSONObject(maximum.toString()).getJSONObject(count.toString())
            assertEquals("$name at $maximum/$count: recorded turns", was.keySet(), now.keySet())
            for (turn in was.keySet()) {
                assertEquals("$name, Maximum Memories $maximum, $count previous messages, $turn",
                    was.getJSONArray(turn).strings(), now.getJSONArray(turn).strings())
            }
        }
    }
}
