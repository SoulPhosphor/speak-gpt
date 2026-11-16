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
import kotlin.math.sqrt

/**
 * Evaluation only. Runs the methods and +3 rules fixed in
 * recall_carryover_holdout/final_methods.json on two sets:
 *  - recall_carryover_set, the development set the methods were worked out on;
 *  - recall_carryover_holdout, written afterwards and used only to check them.
 *
 * Only plan_1, plan_2 and plan_3 are the app's helper ([PreviousMessageSearch]).
 * The other methods and every +3 rule are written here, are not in the app,
 * and are not approved. Nothing in this file changes what the app sends.
 *
 * Each set is scored from two inputs, both from the app's real q4 model:
 *  - "android": similarities measured on Android ONNX Runtime 1.20 by the
 *    emulator job of Android Checks (android_ort120_similarities.json),
 *    replayed here on a desktop JVM;
 *  - "reference_vectors": vectors made on a desktop with the same model.
 *
 * Every turn goes through the enforcer's selection
 * ([RetrievalBackfill.select]) with Maximum Memories and the +3 rule. The
 * sets have no lore and no oversized memory, so nothing is filtered out.
 *
 * Counting, per turn: correct = expected memories sent; missed = expected
 * memories not sent; acceptable = sent memories the cases mark as reasonable
 * but not required; incorrect = everything else sent. An incorrect memory
 * rated +3 is also counted as a sensitive false match.
 */
class CarryOverFinalEvaluationTest {

    private val params = LiveRetrieval.Params()
    private val defaultMaximum = RetrievalPolicy.DEFAULT_TOP_K
    private val maximums = listOf(1, 2, 3, 5, defaultMaximum)
    private val query2d = floatArrayOf(1f, 0f)

    private fun text(path: String): String? =
        javaClass.classLoader!!.getResourceAsStream(path)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONArray.vector() = FloatArray(length()) { getDouble(it).toFloat() }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private val methodsText = checkNotNull(text("recall_carryover_holdout/final_methods.json"))
    private val methods = JSONObject(methodsText).getJSONArray("methods").objects()
    private val plusThreeRules = JSONObject(methodsText).getJSONArray("plus_three_rules").objects()

    private class Turn(
        val id: String, val kind: String, val text: String,
        val expect: Set<String>, val acceptable: Set<String>,
        /** For an unnamed follow-up: how many unnamed follow-ups or asides come straight before it. */
        val depth: Int
    )

    /** One set read from one input. */
    private inner class Data(val folder: String, val source: String) {
        val casesText = checkNotNull(text("$folder/cases.json"))
        private val cases = JSONObject(casesText)
        private val memories = cases.getJSONArray("memories").objects()
        val importance = memories.associate { it.getString("id") to it.optInt("importance", 0) }
        val conversations: List<List<Turn>> = cases.getJSONArray("conversations").objects().map { c ->
            var run = 0
            c.getJSONArray("messages").objects().map {
                val kind = it.getString("kind")
                val turn = Turn(it.getString("id"), kind, it.getString("text"),
                    it.getJSONArray("expect").strings().toSet(), it.getJSONArray("acceptable").strings().toSet(), run)
                run = if (kind == "follow_up_unnamed" || kind == "aside") run + 1 else 0
                turn
            }
        }
        private val vectors = JSONObject(checkNotNull(text("$folder/vectors.json")))
        private val sectionVectors = JSONObject(checkNotNull(text("$folder/section_vectors.json"))).getJSONObject("messages")
        private val android = if (source == "android")
            JSONObject(checkNotNull(text("$folder/android_ort120_similarities.json"))) else null

        private fun memory(m: JSONObject) = RetrievableMemory(
            memoryId = m.getString("id"), scope = "global", content = m.getString("content"),
            embeddingText = null, importance = m.optInt("importance", 0),
            createdAt = "2028-01-01T00:00:00Z", worldId = null
        )
        private val referenceIndex = LiveRetrieval.index(memories.map {
            LiveRetrieval.Candidate(memory(it), vectors.getJSONArray(it.getString("id")).vector(),
                it.getJSONArray("tags").strings(), emptyList())
        })
        val memoryCount = memories.size

        init {
            android?.let {
                assertEquals("$folder: Android similarities are for other cases", hash(casesText), it.getString("cases_sha256"))
                assertEquals(memories.map { m -> m.getString("id") }, it.getJSONArray("memory_ids").strings())
                assertEquals("1.20.0", it.getString("runtime"))
            }
        }

        /** An index whose vectors reproduce exactly the measured similarities. */
        private fun measuredIndex(scores: JSONArray): LiveRetrieval.Index {
            assertEquals(memories.size, scores.length())
            return LiveRetrieval.index(memories.mapIndexed { i, m ->
                val similarity = scores.getDouble(i)
                LiveRetrieval.Candidate(memory(m),
                    floatArrayOf(similarity.toFloat(), sqrt(1.0 - similarity * similarity).toFloat()),
                    m.getJSONArray("tags").strings(), emptyList())
            })
        }

        /** The message's own search, whole message and sections, cut at [limit] candidates. */
        fun own(turn: Turn, limit: Int): SavedMemorySearch.Result {
            val recorded = sectionVectors.optJSONArray(turn.id)?.objects().orEmpty()
            val cut = LongMessageSearch.cut(turn.text)
            assertEquals("${turn.id} is cut differently from the recorded sections",
                recorded.map { it.getString("text") }, cut.map { it.text })
            if (android == null) {
                return SavedMemorySearch.search(turn.text, vectors.getJSONArray(turn.id).vector(), referenceIndex,
                    params, limit, true) {
                    LongMessageSearch.read(turn.text, referenceIndex, now = { 0L }) { text ->
                        recorded.single { it.getString("text") == text }.getJSONArray("vector").vector()
                    }
                }
            }
            val whole = SavedMemorySearch.search(turn.text, query2d,
                measuredIndex(android.getJSONObject("messages").getJSONArray(turn.id)), params, limit, true) {
                LongMessageSearch.Reading.NONE
            }
            if (cut.isEmpty()) return whole
            // Each section has its own measured similarities, so its matches
            // are worked out here with the app's own section rule.
            val measured = android.getJSONObject("sections").getJSONArray(turn.id)
            assertEquals(cut.size, measured.length())
            val settings = LongMessageSearch.Settings()
            val already = whole.whole.explanations.filter { it.passed }.map { it.memory.memoryId }.toSet()
            val best = LinkedHashMap<String, LongMessageSearch.Match>()
            cut.forEachIndexed { n, section ->
                val index = measuredIndex(measured.getJSONArray(n))
                for (e in LiveRetrieval.explain(section.text, query2d, index, params)) {
                    val match = LongMessageSearch.Match(section, cut.size, e)
                    if (!LongMessageSearch.Evaluation(match, settings.sectionSimilarityMin).qualifies) continue
                    val id = e.memory.memoryId
                    if (id in already) continue
                    if ((best[id]?.explanation?.score ?: Double.NEGATIVE_INFINITY) < e.score) best[id] = match
                }
            }
            val matches = best.values.sortedByDescending { it.explanation.score }
            val included = ImportanceRanking.includeMandatory(matches, (limit - whole.ranked.size).coerceAtLeast(0)) {
                ImportanceRanking.isMandatory(it.explanation.memory.importance.toDouble(), true)
            }
            return whole.copy(ranked = whole.ranked + included.map { it.scored },
                sectionMatches = matches, sectionIds = included.map { it.explanation.memory.memoryId }.toSet())
        }
    }

    /** A candidate and whether an earlier message supplied it. */
    private data class Candidate(val scored: ScoredMemory, val carried: Boolean)
    private data class Sent(val id: String, val carried: Boolean)

    private fun select(candidates: List<Candidate>, maximum: Int): List<Sent> {
        val carried = candidates.associate { it.scored.memory.memoryId to it.carried }
        return RetrievalBackfill.select(
            candidates.map { it.scored }, maximum, RetrievalBackfill.scanCap(maximum),
            isMandatory = { ImportanceRanking.isMandatory(it.memory.importance.toDouble(), true) }
        ) { true }.kept.map { Sent(it.memory.memoryId, carried.getValue(it.memory.memoryId)) }
    }

    private fun combined(
        current: SavedMemorySearch.Result, earlier: List<SavedMemorySearch.Result>, limit: Int
    ) = PreviousMessageSearch.combine(current, earlier, limit, true).found
        .map { Candidate(it.scored, it.messagesBack > 0) }

    /** What [method] sends for every turn of [turns], in order. */
    private fun run(data: Data, method: JSONObject, turns: List<Turn>, maximum: Int): List<List<Sent>> {
        val limit = RetrievalBackfill.scanCap(maximum) + 1
        val everything = maxOf(limit, data.memoryCount)
        var subject = emptyList<ScoredMemory>()
        var carriedFor = 0
        return turns.indices.map { position ->
            val current = data.own(turns[position], limit)
            fun previous(count: Int) = turns.subList(0, position).reversed().take(count)
                .map { data.own(it, everything) }
            val candidates = when (val id = method.getString("id")) {
                "none" -> combined(current, emptyList(), limit)
                "plan_1", "plan_2", "plan_3" -> combined(current, previous(method.getInt("previous_messages")), limit)
                "similar_to_new_message" -> {
                    val similar = current.whole.explanations
                        .filter { (it.similarity ?: 0.0) >= method.getDouble("minimum_similarity") }
                        .map { it.memory.memoryId }.toSet()
                    combined(current, previous(1).map { p ->
                        p.copy(ranked = p.ranked.filter { it.memory.memoryId in similar })
                    }, limit)
                }
                "subject_anchor", "subject_anchor_3_turns" -> {
                    val standout = method.getDouble("confident_standout")
                    val numbers = current.whole.explanations.associateBy { it.memory.memoryId }
                    val confident = current.ranked.filter { scored ->
                        val e = numbers.getValue(scored.memory.memoryId)
                        scored.memory.memoryId in current.sectionIds ||
                            (e.passedMeaning && e.passedKeyword) ||
                            (e.passedMeaning && (e.standout ?: 0.0) >= standout)
                    }
                    val own = current.ranked.map { Candidate(it, false) }
                    when {
                        confident.isNotEmpty() -> {
                            subject = confident
                            carriedFor = 0
                            own
                        }
                        subject.isNotEmpty() && (!method.has("turns") || carriedFor < method.getInt("turns")) -> {
                            carriedFor++
                            val have = current.ranked.map { it.memory.memoryId }.toSet()
                            own + subject.filter { it.memory.memoryId !in have }.map { Candidate(it, true) }
                        }
                        else -> own
                    }
                }
                else -> error("Unknown method $id")
            }
            select(candidates, maximum)
        }
    }

    private class Tally {
        var correct = 0
        var missed = 0
        var acceptable = 0
        var incorrectOwn = 0
        var incorrectCarriedOldSubject = 0
        var incorrectCarriedOther = 0
        var incorrectPlusThree = 0
        var plusThreeExpectedMissed = 0
        var sentPastMaximum = 0
        var clean = 0
        val complete = HashMap<String, Int>()
        val turns = HashMap<String, Int>()
        val incorrect get() = incorrectOwn + incorrectCarriedOldSubject + incorrectCarriedOther

        fun json(): JSONObject = JSONObject()
            .put("correct", correct).put("missed", missed).put("incorrect", incorrect)
            .put("incorrect_from_the_new_message", incorrectOwn)
            .put("incorrect_carried_earlier_subject", incorrectCarriedOldSubject)
            .put("incorrect_carried_other", incorrectCarriedOther)
            .put("incorrect_rated_plus_three", incorrectPlusThree)
            .put("acceptable_sent", acceptable).put("turns_with_nothing_incorrect", clean)
            .put("expected_plus_three_missed", plusThreeExpectedMissed).put("sent_past_maximum", sentPastMaximum)
            .put("turns_complete", JSONObject(complete.toSortedMap() as Map<*, *>))
            .put("turns", JSONObject(turns.toSortedMap() as Map<*, *>))
    }

    private fun bucket(turn: Turn) = when {
        turn.kind != "follow_up_unnamed" -> turn.kind
        turn.depth == 0 -> "unnamed_follow_up_after_a_naming_message"
        turn.depth == 1 -> "unnamed_follow_up_second_in_a_row"
        else -> "unnamed_follow_up_third_or_later_in_a_row"
    }

    private fun score(data: Data, method: JSONObject, maximum: Int, record: JSONObject? = null): Tally {
        val tally = Tally()
        val none = methods.first()
        for (turns in data.conversations) {
            val results = run(data, method, turns, maximum)
            val alone = run(data, none, turns, maximum)
            for (position in turns.indices) {
                val turn = turns[position]
                val sent = results[position]
                val ids = sent.map { it.id }
                assertEquals("${turn.id}: listed twice", ids.size, ids.toSet().size)
                val ownIds = alone[position].map { it.id }
                assertEquals("${turn.id} ${method.getString("id")}: the new message's own result comes first, unchanged",
                    ownIds, ids.take(ownIds.size))
                val earlierSubjects = turns.subList(0, position).flatMap { it.expect }.toSet()
                for (item in sent) when {
                    item.id in turn.expect -> tally.correct++
                    item.id in turn.acceptable -> tally.acceptable++
                    else -> {
                        when {
                            !item.carried -> tally.incorrectOwn++
                            item.id in earlierSubjects -> tally.incorrectCarriedOldSubject++
                            else -> tally.incorrectCarriedOther++
                        }
                        if (data.importance.getValue(item.id) >= 3) tally.incorrectPlusThree++
                    }
                }
                val missed = turn.expect.filter { it !in ids }
                tally.missed += missed.size
                tally.plusThreeExpectedMissed += missed.count { data.importance.getValue(it) >= 3 }
                tally.sentPastMaximum += (ids.size - maximum).coerceAtLeast(0)
                val kind = bucket(turn)
                tally.turns[kind] = (tally.turns[kind] ?: 0) + 1
                if (missed.isEmpty()) tally.complete[kind] = (tally.complete[kind] ?: 0) + 1
                if (ids.all { it in turn.expect || it in turn.acceptable }) tally.clean++
                record?.put(turn.id, JSONArray(sent.map { if (it.carried) "${it.id}@carried" else it.id }))
            }
        }
        return tally
    }

    /**
     * What a lower bar for +3 memories would add to each message searched on
     * its own: +3 memories it would newly find where expected, and +3
     * memories it would newly send where they are neither expected nor
     * acceptable.
     */
    private fun plusThree(data: Data, rule: JSONObject): JSONObject {
        var found = 0
        var acceptable = 0
        val wrong = JSONArray()
        if (rule.has("standout")) for (turn in data.conversations.flatten()) {
            val own = data.own(turn, data.memoryCount)
            val passed = own.ranked.map { it.memory.memoryId }.toSet()
            val numbers = own.whole.explanations
            val top = numbers.maxByOrNull { it.similarity ?: -1.0 }?.memory?.memoryId
            for (e in numbers) {
                val id = e.memory.memoryId
                if (data.importance.getValue(id) < 3 || id in passed) continue
                val similarity = e.similarity ?: continue
                if (similarity < params.similarityFloor || (e.standout ?: 0.0) < rule.getDouble("standout")) continue
                if (rule.getString("id") == "lower_bar_top_match_only" && id != top) continue
                when (id) {
                    in turn.expect -> found++
                    in turn.acceptable -> acceptable++
                    else -> wrong.put("${turn.id}:$id")
                }
            }
        }
        return JSONObject().put("expected_plus_three_newly_found", found)
            .put("acceptable_plus_three_newly_sent", acceptable)
            .put("plus_three_newly_sent_where_not_wanted", wrong.length()).put("where", wrong)
    }

    private fun evaluate(folder: String) {
        val measured = JSONObject().put("methods_sha256", hash(methodsText))
        for (source in listOf("android", "reference_vectors")) {
            val data = Data(folder, source)
            measured.put("cases_sha256", hash(data.casesText))
            val summary = JSONObject()
            val perTurn = JSONObject()
            for (method in methods) {
                val id = method.getString("id")
                val byMaximum = JSONObject()
                for (maximum in maximums) {
                    val record = if (maximum == defaultMaximum) JSONObject() else null
                    val tally = score(data, method, maximum, record)
                    byMaximum.put(maximum.toString(), tally.json())
                    record?.let { perTurn.put(id, it) }
                    if (maximum == defaultMaximum) println("FINAL $folder $source method=$id ${tally.json()}")
                }
                summary.put(id, byMaximum)
            }
            val rules = JSONObject()
            for (rule in plusThreeRules) {
                rules.put(rule.getString("id"), plusThree(data, rule))
                println("FINAL $folder $source plus_three=${rule.getString("id")} ${rules.getJSONObject(rule.getString("id"))}")
            }
            measured.put(source, JSONObject().put("summary", summary)
                .put("sent_at_default_maximum", perTurn).put("plus_three_rules", rules))
        }
        println("FINAL RECORD $folder $measured")

        // Results are printed before this read. Record measured results by
        // hand; never invent a passing record here.
        val recordedText = checkNotNull(text("$folder/final_results.json")) {
            "$folder: the results are not recorded yet; record the printed FINAL RECORD line."
        }
        val recorded = JSONObject(recordedText)
        assertEquals("Expected answers must stay fixed", recorded.getString("cases_sha256"), measured.getString("cases_sha256"))
        assertEquals("The methods must stay fixed", recorded.getString("methods_sha256"), measured.getString("methods_sha256"))
        for (source in listOf("android", "reference_vectors")) {
            assertTrue("$folder $source differs from the record",
                measured.getJSONObject(source).similar(recorded.getJSONObject(source)))
        }
    }

    @Test
    fun developmentSetMatchesTheRecord() = evaluate("recall_carryover_set")

    @Test
    fun heldOutSetMatchesTheRecord() = evaluate("recall_carryover_holdout")
}
