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
import org.teslasoft.assistant.preferences.memory.RetrievableMemory
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * Scores the saved-memory test set's nine conversations with
 * [PreviousMessageSearch] at 0, 1, 2 and 3 previous messages
 * (conversation_recall_plan.md Step 3). Each conversation is its own chat, so
 * its first turn has no previous message.
 *
 * Two sets of measured inputs are scored, both recorded before this step:
 *  - "reference_vectors": the frozen full vectors in vectors.json;
 *  - "android_ort120_scores": the similarities the real model produced on
 *    Android ONNX Runtime 1.20, replayed as in NativeMemoryGateRegressionTest.
 * Nothing here is new model output, and no expected answer is changed.
 *
 * The exact memories sent for every turn at every count are recorded in
 * previous_message_results.json; the test fails when they change.
 */
class PreviousMessageRecallTest {

    private val limit = 5
    private val params = LiveRetrieval.Params()

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

    /** One way of searching a turn with its [count] nearest previous turns. */
    private fun interface Source {
        fun search(turns: List<Turn>, position: Int, count: Int): PreviousMessageSearch.Result
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
        return Source { turns, position, count ->
            val current = turns[position]
            val previous = turns.subList(0, position).reversed()
            val result = PreviousMessageSearch.search(
                PreviousMessageSearch.Message(current.text, vector(current.id)),
                previous.map { PreviousMessageSearch.Message(it.text, vector(it.id)) },
                count, index, params, limit, true
            )
            // The same answer when the searches are run first and combined after.
            val combined = PreviousMessageSearch.combine(
                own(current.text, vector(current.id), index, limit),
                previous.take(count).map { own(it.text, vector(it.id), index, index.corpus.size) },
                limit, true
            )
            assertEquals("${current.id} at $count", result.found, combined.found)
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
        return Source { turns, position, count ->
            val current = turns[position]
            PreviousMessageSearch.combine(
                own(current.text, query, index(current.id), limit),
                turns.subList(0, position).reversed().take(count).map {
                    own(it.text, query, index(it.id), memories.size)
                },
                limit, true
            )
        }
    }

    private class Tally {
        var firstFollowUpsKept = 0
        var firstFollowUps = 0
        var laterFollowUpsKept = 0
        var laterFollowUps = 0
        var newSubjectsFound = 0
        var subjectChanges = 0
        val oldSubjectAfterChange = ArrayList<String>()
        val otherUnexpected = ArrayList<String>()
        var correct = 0
        var missed = 0
        var leftOutByLimit = 0
    }

    private fun score(source: Source, count: Int, record: JSONObject): Tally {
        val tally = Tally()
        for (turns in conversations) {
            val oldSubject = turns.first().expect
            for (position in turns.indices) {
                val turn = turns[position]
                val result = source.search(turns, position, count)
                val sent = result.ranked.map { it.memory.memoryId }
                val own = source.search(turns, position, 0).ranked.map { it.memory.memoryId }

                // What the step must guarantee, whatever the numbers are.
                assertEquals("${turn.id}: listed twice", sent.size, sent.toSet().size)
                assertEquals("${turn.id}: the new message's own result must come first, unchanged",
                    own, sent.take(own.size))
                assertEquals("${turn.id}: its own result is attributed to it",
                    own.size, result.found.count { it.messagesBack == 0 })
                assertEquals("${turn.id}: older matches must follow newer ones",
                    result.found.map { it.messagesBack }.sorted(), result.found.map { it.messagesBack })
                assertTrue("${turn.id}: nothing is carried further than the count",
                    result.found.all { it.messagesBack <= minOf(count, position) })
                for (found in result.found.filter { it.messagesBack > 0 }) {
                    val earlier = source.search(turns, position - found.messagesBack, 0)
                    assertTrue("${turn.id}: ${found.scored.memory.memoryId} did not pass for the earlier message",
                        found.scored.memory.memoryId in earlier.ranked.map { it.memory.memoryId })
                }
                if (count == 0) assertTrue(result.previous.isEmpty())

                val kept = sent.containsAll(turn.expect)
                val last = position == turns.lastIndex
                when {
                    position == 0 -> Unit
                    last -> {
                        tally.subjectChanges++
                        if (kept) tally.newSubjectsFound++
                    }
                    position == 1 -> {
                        tally.firstFollowUps++
                        if (kept) tally.firstFollowUpsKept++
                    }
                    else -> {
                        tally.laterFollowUps++
                        if (kept) tally.laterFollowUpsKept++
                    }
                }
                tally.correct += sent.count { it in turn.expect }
                tally.missed += turn.expect.count { it !in sent }
                tally.leftOutByLimit += result.leftOutByLimit.size
                for (found in result.found) {
                    val id = found.scored.memory.memoryId
                    if (id in turn.expect) continue
                    val entry = "${turn.id}:$id@${found.messagesBack}"
                    if (last && id in oldSubject) tally.oldSubjectAfterChange.add(entry)
                    else tally.otherUnexpected.add(entry)
                }
                record.put(turn.id, JSONArray(result.found.map { "${it.scored.memory.memoryId}@${it.messagesBack}" }))
            }
        }
        return tally
    }

    @Test
    fun conversationsScoredAtZeroToThreePreviousMessagesMatchTheRecord() {
        assertEquals("Six three-turn and three four-turn conversations",
            listOf(3, 3, 3, 3, 3, 3, 4, 4, 4), conversations.map { it.size })
        val measured = JSONObject().put("cases_sha256", hash(casesText)).put("memory_limit", limit)
        for ((name, source) in listOf("reference_vectors" to referenceVectors(), "android_ort120_scores" to androidScores())) {
            val bySetting = JSONObject()
            for (count in 0..PreviousMessageSearch.MAX_PREVIOUS_MESSAGES) {
                val record = JSONObject()
                val t = score(source, count, record)
                bySetting.put(count.toString(), record)
                println("PREVIOUS $name count=$count first_follow_ups_kept=${t.firstFollowUpsKept}/${t.firstFollowUps} " +
                    "later_follow_ups_kept=${t.laterFollowUpsKept}/${t.laterFollowUps} " +
                    "new_subjects_found=${t.newSubjectsFound}/${t.subjectChanges} " +
                    "old_subject_after_change=${t.oldSubjectAfterChange.size} other_unexpected=${t.otherUnexpected.size} " +
                    "correct=${t.correct} missed=${t.missed} left_out_by_limit=${t.leftOutByLimit}")
                println("PREVIOUS $name count=$count old_subject_after_change ${t.oldSubjectAfterChange}")
                println("PREVIOUS $name count=$count other_unexpected ${t.otherUnexpected}")
            }
            measured.put(name, bySetting)
        }
        println("PREVIOUS RECORD ${measured.toString()}")

        // Results are printed before this read. Record measured results by
        // hand; never invent a passing record here.
        val stream = javaClass.classLoader!!.getResourceAsStream("recall_test_set/previous_message_results.json")
        checkNotNull(stream) { "The results are not recorded yet; record the printed PREVIOUS RECORD line." }
        val recorded = JSONObject(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        assertEquals("Expected answers must stay fixed", recorded.getString("cases_sha256"), hash(casesText))
        assertEquals(recorded.getInt("memory_limit"), limit)
        for (name in listOf("reference_vectors", "android_ort120_scores")) {
            for (count in 0..PreviousMessageSearch.MAX_PREVIOUS_MESSAGES) {
                val was = recorded.getJSONObject(name).getJSONObject(count.toString())
                val now = measured.getJSONObject(name).getJSONObject(count.toString())
                assertEquals("$name at $count: recorded turns", was.keySet(), now.keySet())
                for (turn in was.keySet()) {
                    assertEquals("$name at $count previous messages, $turn",
                        was.getJSONArray(turn).strings(), now.getJSONArray(turn).strings())
                }
            }
        }
    }
}
