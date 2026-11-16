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
import org.teslasoft.assistant.preferences.memory.ScoredMemory
import org.teslasoft.assistant.preferences.memory.enforcer.RetrievalBackfill
import java.security.MessageDigest

/**
 * Evaluation only. Compares ways of carrying saved memories into follow-up
 * messages on the independent carry-over set (recall_carryover_set), whose
 * expected answers and list of methods were fixed before any method was run.
 *
 * Only plan_1, plan_2 and plan_3 are the app's helper ([PreviousMessageSearch])
 * and the method in conversation_recall_plan.md G.9a. Every other method is
 * written here, in this test, is not in the app, and is not approved. Nothing
 * in this file changes what the app sends.
 *
 * Every turn goes the way a sent message goes: candidates, then the
 * enforcer's selection ([RetrievalBackfill.select]) with Maximum Memories and
 * the +3 rule. The set has no lore and no oversized memory, so the enforcer
 * filters nothing out here.
 *
 * Vectors are from the app's real q4 model, made by
 * recall_test_set/generate_vectors.py and generate_section_vectors.py with
 * ONNX Runtime 1.20.0 on a desktop machine. They are not Android output.
 *
 * Counting, per turn: correct = expected memories sent; missed = expected
 * memories not sent; acceptable = sent memories the cases mark as reasonable
 * but not required (neither correct nor incorrect); incorrect = everything
 * else that was sent.
 */
class CarryOverEvaluationTest {

    private val params = LiveRetrieval.Params()
    private val defaultMaximum = RetrievalPolicy.DEFAULT_TOP_K
    private val maximums = listOf(1, 2, 3, 5, defaultMaximum)

    private fun resource(name: String): String = checkNotNull(
        javaClass.classLoader!!.getResourceAsStream("recall_carryover_set/$name")
    ) { "Missing carry-over fixture: $name" }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONArray.vector() = FloatArray(length()) { getDouble(it).toFloat() }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private class Turn(
        val id: String, val kind: String, val text: String,
        val expect: Set<String>, val acceptable: Set<String>
    )

    private val casesText = resource("cases.json")
    private val cases = JSONObject(casesText)
    private val methodsText = resource("methods.json")
    private val methods = JSONObject(methodsText).getJSONArray("methods").objects()
    private val vectors = JSONObject(resource("vectors.json"))
    private val sectionVectors = JSONObject(resource("section_vectors.json")).getJSONObject("messages")
    private val memories = cases.getJSONArray("memories").objects()
    private val importance = memories.associate { it.getString("id") to it.optInt("importance", 0) }
    private val conversations: List<List<Turn>> = cases.getJSONArray("conversations").objects().map { c ->
        c.getJSONArray("messages").objects().map {
            Turn(it.getString("id"), it.getString("kind"), it.getString("text"),
                it.getJSONArray("expect").strings().toSet(), it.getJSONArray("acceptable").strings().toSet())
        }
    }
    private val index = LiveRetrieval.index(memories.map {
        LiveRetrieval.Candidate(
            RetrievableMemory(
                memoryId = it.getString("id"), scope = "global", content = it.getString("content"),
                embeddingText = null, importance = it.optInt("importance", 0),
                createdAt = "2028-01-01T00:00:00Z", worldId = null
            ),
            vectors.getJSONArray(it.getString("id")).vector(), it.getJSONArray("tags").strings(), emptyList()
        )
    })

    private fun vector(turn: Turn) = vectors.getJSONArray(turn.id).vector()

    /** The turn's section reading from the recorded real-model vectors. */
    private fun reading(turn: Turn): LongMessageSearch.Reading {
        val recorded = sectionVectors.optJSONArray(turn.id)?.objects().orEmpty()
        assertEquals("${turn.id} is cut differently from the recorded sections",
            recorded.map { it.getString("text") }, LongMessageSearch.cut(turn.text).map { it.text })
        return LongMessageSearch.read(turn.text, index, now = { 0L }) { text ->
            recorded.single { it.getString("text") == text }.getJSONArray("vector").vector()
        }
    }

    private fun own(turn: Turn, limit: Int) =
        // Recorded before the +3 top-match rule; replayed with it off.
        SavedMemorySearch.search(turn.text, vector(turn), index, params, limit, true,
            plusThreeTopMatch = false) { reading(turn) }

    private fun message(turn: Turn) =
        PreviousMessageSearch.Message.withVector(turn.text, vector(turn)) { reading(turn) }

    /** One sent memory: its id and whether an earlier message supplied it. */
    private data class Sent(val id: String, val carried: Boolean)

    private fun select(candidates: List<Pair<ScoredMemory, Boolean>>, maximum: Int): List<Sent> {
        val carried = candidates.associate { it.first.memory.memoryId to it.second }
        return RetrievalBackfill.select(
            candidates.map { it.first }, maximum, RetrievalBackfill.scanCap(maximum),
            isMandatory = { ImportanceRanking.isMandatory(it.memory.importance.toDouble(), true) }
        ) { true }.kept.map { Sent(it.memory.memoryId, carried.getValue(it.memory.memoryId)) }
    }

    /** The app's helper, with [count] previous messages. */
    private fun plan(turns: List<Turn>, position: Int, count: Int, maximum: Int): List<Sent> {
        val result = PreviousMessageSearch.search(
            message(turns[position]), turns.subList(0, position).reversed().map { message(it) },
            count, index, params, RetrievalBackfill.scanCap(maximum) + 1, true,
            plusThreeTopMatch = false
        )
        assertTrue(result.searchedByKeywordsOnly.isEmpty())
        return select(result.found.map { it.scored to (it.messagesBack > 0) }, maximum)
    }

    /**
     * The new message's own candidates, then those of the previous message's
     * matches that [keep] lets through. Evaluation only.
     */
    private fun filtered(
        turns: List<Turn>, position: Int, maximum: Int,
        keep: (current: SavedMemorySearch.Result, previous: SavedMemorySearch.Result) -> List<ScoredMemory>
    ): List<Sent> {
        val limit = RetrievalBackfill.scanCap(maximum) + 1
        val current = own(turns[position], limit)
        val earlier = if (position == 0) emptyList() else {
            val previous = own(turns[position - 1], index.corpus.size)
            listOf(previous.copy(ranked = keep(current, previous)))
        }
        val result = PreviousMessageSearch.combine(current, earlier, limit, true)
        return select(result.found.map { it.scored to (it.messagesBack > 0) }, maximum)
    }

    /** What [method] sends for every turn of [turns], in order. */
    private fun run(method: JSONObject, turns: List<Turn>, maximum: Int): List<List<Sent>> {
        val out = ArrayList<List<Sent>>()
        for (position in turns.indices) {
            val sent = when (val id = method.getString("id")) {
                "none" -> plan(turns, position, 0, maximum)
                "plan_1", "plan_2", "plan_3" -> plan(turns, position, method.getInt("previous_messages"), maximum)
                "only_if_nothing" -> plan(turns, position, 0, maximum).ifEmpty { plan(turns, position, 1, maximum) }
                "inherit_if_nothing" -> plan(turns, position, 0, maximum).ifEmpty {
                    out.lastOrNull().orEmpty().map { Sent(it.id, carried = true) }
                }
                "previous_meaning_and_keyword" -> filtered(turns, position, maximum) { _, previous ->
                    val both = previous.whole.explanations.filter { it.passedMeaning && it.passedKeyword }
                        .map { it.memory.memoryId }.toSet()
                    previous.ranked.filter { it.memory.memoryId in both || it.memory.memoryId in previous.sectionIds }
                }
                "similar_to_new_message" -> filtered(turns, position, maximum) { current, previous ->
                    val minimum = method.getDouble("minimum_similarity")
                    val similar = current.whole.explanations.filter { (it.similarity ?: 0.0) >= minimum }
                        .map { it.memory.memoryId }.toSet()
                    previous.ranked.filter { it.memory.memoryId in similar }
                }
                "previous_top_match" -> filtered(turns, position, maximum) { _, previous -> previous.ranked.take(1) }
                else -> error("Unknown method $id")
            }
            out.add(sent)
        }
        return out
    }

    private val kinds = listOf("first", "follow_up_unnamed", "follow_up_named", "subject_change", "return", "aside")

    private class Tally {
        var correct = 0
        var missed = 0
        var acceptable = 0
        var incorrectOwn = 0
        var incorrectCarriedOldSubject = 0
        var incorrectCarriedOther = 0
        var plusThreeExpectedMissed = 0
        var sentPastMaximum = 0
        /** Turns of each kind where every expected memory was sent. */
        val complete = HashMap<String, Int>()
        val turns = HashMap<String, Int>()
        /** Turns with no incorrect memory at all. */
        var clean = 0
        val incorrect get() = incorrectOwn + incorrectCarriedOldSubject + incorrectCarriedOther

        fun json(): JSONObject = JSONObject()
            .put("correct", correct).put("missed", missed).put("incorrect", incorrect)
            .put("incorrect_from_the_new_message", incorrectOwn)
            .put("incorrect_carried_earlier_subject", incorrectCarriedOldSubject)
            .put("incorrect_carried_other", incorrectCarriedOther)
            .put("acceptable_sent", acceptable).put("turns_with_nothing_incorrect", clean)
            .put("expected_plus_three_missed", plusThreeExpectedMissed).put("sent_past_maximum", sentPastMaximum)
            .put("turns_complete", JSONObject(complete.toSortedMap() as Map<*, *>))
    }

    private fun score(method: JSONObject, maximum: Int, record: JSONObject? = null): Tally {
        val tally = Tally()
        for (turns in conversations) {
            val results = run(method, turns, maximum)
            val alone = run(methods.first(), turns, maximum)
            for (position in turns.indices) {
                val turn = turns[position]
                val sent = results[position]
                val ids = sent.map { it.id }
                assertEquals("${turn.id}: listed twice", ids.size, ids.toSet().size)
                // Every method leaves the new message's own result first and unchanged.
                val ownIds = alone[position].map { it.id }
                assertEquals("${turn.id} ${method.getString("id")}: own result first",
                    ownIds, ids.take(ownIds.size))
                val earlierSubjects = turns.subList(0, position).flatMap { it.expect }.toSet()
                for (item in sent) when {
                    item.id in turn.expect -> tally.correct++
                    item.id in turn.acceptable -> tally.acceptable++
                    !item.carried -> tally.incorrectOwn++
                    item.id in earlierSubjects -> tally.incorrectCarriedOldSubject++
                    else -> tally.incorrectCarriedOther++
                }
                val missed = turn.expect.filter { it !in ids }
                tally.missed += missed.size
                tally.plusThreeExpectedMissed += missed.count { importance.getValue(it) >= 3 }
                tally.sentPastMaximum += (ids.size - maximum).coerceAtLeast(0)
                tally.turns[turn.kind] = (tally.turns[turn.kind] ?: 0) + 1
                if (missed.isEmpty()) tally.complete[turn.kind] = (tally.complete[turn.kind] ?: 0) + 1
                if (ids.all { it in turn.expect || it in turn.acceptable }) tally.clean++
                record?.put(turn.id, JSONArray(sent.map { if (it.carried) "${it.id}@carried" else it.id }))
            }
        }
        return tally
    }

    @Test
    fun theSetIsWhatTheResultsDescribe() {
        assertEquals(34, memories.size)
        assertEquals(13, conversations.size)
        assertEquals(79, conversations.sumOf { it.size })
        val ids = memories.map { it.getString("id") }.toSet()
        for (turn in conversations.flatten()) {
            assertTrue("${turn.id}: unknown kind ${turn.kind}", turn.kind in kinds)
            assertTrue("${turn.id}: unknown memory", ids.containsAll(turn.expect + turn.acceptable))
            assertTrue("${turn.id}: a memory is both expected and acceptable",
                turn.expect.intersect(turn.acceptable).isEmpty())
            assertEquals("${turn.id}: an aside expects nothing", turn.kind == "aside", turn.expect.isEmpty())
        }
        assertEquals(setOf("pet_otis", "health_allergy", "family_brother"),
            importance.filterValues { it >= 3 }.keys)
        assertTrue("Every memory and turn has a vector",
            vectors.keySet().containsAll(ids + conversations.flatten().map { it.id }))
        assertEquals(listOf("none", "plan_1", "plan_2", "plan_3", "only_if_nothing", "inherit_if_nothing",
            "previous_meaning_and_keyword", "similar_to_new_message", "previous_top_match"),
            methods.map { it.getString("id") })
    }

    @Test
    fun everyMethodAtEveryMaximumMatchesTheRecord() {
        val measured = JSONObject()
            .put("cases_sha256", hash(casesText)).put("methods_sha256", hash(methodsText))
        val summary = JSONObject()
        val perTurn = JSONObject()
        for (method in methods) {
            val id = method.getString("id")
            val byMaximum = JSONObject()
            for (maximum in maximums) {
                val record = if (maximum == defaultMaximum) JSONObject() else null
                val tally = score(method, maximum, record)
                byMaximum.put(maximum.toString(), tally.json())
                record?.let { perTurn.put(id, it) }
                println("CARRYOVER method=$id maximum=$maximum ${tally.json()}")
            }
            summary.put(id, byMaximum)
        }
        val counts = Tally().also { t -> conversations.flatten().forEach { t.turns[it.kind] = (t.turns[it.kind] ?: 0) + 1 } }
        measured.put("turns_by_kind", JSONObject(counts.turns.toSortedMap() as Map<*, *>))
        measured.put("summary", summary).put("sent_at_default_maximum", perTurn)
        println("CARRYOVER RECORD $measured")

        // Results are printed before this read. Record measured results by
        // hand; never invent a passing record here.
        val stream = javaClass.classLoader!!.getResourceAsStream("recall_carryover_set/results.json")
        checkNotNull(stream) { "The results are not recorded yet; record the printed CARRYOVER RECORD line." }
        val recorded = JSONObject(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        assertEquals("Expected answers must stay fixed", recorded.getString("cases_sha256"), hash(casesText))
        assertEquals("The compared methods must stay fixed", recorded.getString("methods_sha256"), hash(methodsText))
        for (key in listOf("turns_by_kind", "summary", "sent_at_default_maximum")) {
            assertTrue("$key differs from the record\nnow: ${measured.getJSONObject(key)}\nwas: ${recorded.getJSONObject(key)}",
                measured.getJSONObject(key).similar(recorded.getJSONObject(key)))
        }
    }
}
