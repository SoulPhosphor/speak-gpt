package org.teslasoft.assistant.preferences.memory.librarian

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.RetrievableMemory
import java.security.MessageDigest
import kotlin.math.abs

/** Fixed, invented answers and real model vectors; no inference or downloads in unit tests. */
class RecallTestSetTest {
    private data class Counts(val found: Int, val missed: Int, val wronglySent: Int) {
        operator fun plus(other: Counts) = Counts(
            found + other.found, missed + other.missed, wronglySent + other.wronglySent
        )
        fun report() = "found=$found missed=$missed wrongly_sent=$wronglySent"
    }

    private data class Outcome(val found: Set<String>, val missed: Set<String>, val incorrect: Set<String>) {
        fun counts() = Counts(found.size, missed.size, incorrect.size)
    }

    private fun outcome(expected: Set<String>, sent: Set<String>) =
        Outcome(expected.intersect(sent), expected - sent, sent - expected)

    private fun regressions(
        id: String, previousFound: Set<String>, previousIncorrect: Set<String>, current: Outcome
    ): List<String> = buildList {
        val lost = previousFound - current.found
        val added = current.incorrect - previousIncorrect
        if (lost.isNotEmpty()) add("$id lost correct memory ids ${lost.sorted()}")
        if (added.isNotEmpty()) add("$id added incorrect memory ids ${added.sorted()}")
    }

    @Test
    fun replacingACorrectMatchCannotHideBehindAnUnchangedCount() {
        val result = outcome(setOf("first", "second"), setOf("second"))
        assertEquals(
            listOf("sample lost correct memory ids [first]"),
            regressions("sample", setOf("first"), emptySet(), result)
        )
    }

    @Test
    fun replacingAnIncorrectMatchCannotHideBehindAnUnchangedCount() {
        val result = outcome(emptySet(), setOf("new_wrong"))
        assertEquals(
            listOf("sample added incorrect memory ids [new_wrong]"),
            regressions("sample", emptySet(), setOf("old_wrong"), result)
        )
    }

    @Test
    fun everyUnexpectedReturnIsIncorrectWithoutNeedingAMustNotEntry() {
        val result = outcome(setOf("expected"), setOf("expected", "unlisted"))
        assertEquals(setOf("unlisted"), result.incorrect)
        assertEquals(Counts(1, 0, 1), result.counts())
    }

    private fun resource(name: String): String = checkNotNull(
        javaClass.classLoader!!.getResourceAsStream("recall_test_set/$name")
    ) { "Missing recall fixture: $name" }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    @Test
    fun currentSearchDoesNotRegressFromTheRecordedBaseline() {
        val casesText = resource("cases.json")
        val cases = JSONObject(casesText)
        val vectors = JSONObject(resource("vectors.json"))
        val memories = cases.getJSONArray("memories").objects()
        val messages = cases.getJSONArray("messages").objects()
        val conversations = cases.getJSONArray("conversations").objects()
        assertTrue("At least 30 memories are required", memories.size >= 30)
        assertTrue("At least nine conversations are required", conversations.size >= 9)
        assertTrue("At least six three-turn conversations are required",
            conversations.count { it.getJSONArray("messages").length() == 3 } >= 6)
        assertTrue("At least three four-turn conversations are required",
            conversations.count { it.getJSONArray("messages").length() == 4 } >= 3)
        conversations.forEach {
            val rows = it.getJSONArray("messages").objects()
            assertTrue("Conversations must have three or four turns", rows.size in 3..4)
            val first = rows.first().getJSONArray("expect").strings().toSet()
            assertTrue("The subject change must exclude the previous subject",
                rows.last().getJSONArray("must_not").strings().containsAll(first))
        }
        val turns = conversations.flatMap { it.getJSONArray("messages").objects() }
        val allMessages = messages + turns
        val memoryIds = memories.map { it.getString("id") }.toSet()
        val ids = memories.map { it.getString("id") } + allMessages.map { it.getString("id") }
        assertEquals("Fixture ids must be unique", ids.size, ids.toSet().size)
        assertEquals("Every fixture has exactly one vector", ids.toSet(), vectors.keySet())
        val minimums = mapOf(
            "short" to 10, "long_typed" to 8, "long_dictated" to 8,
            "dictated_bad_periods" to 5, "very_long" to 4,
            "accidental_word" to 8, "nothing_relevant" to 8, "two_subjects" to 5
        )
        for ((kind, minimum) in minimums) {
            assertTrue("Not enough $kind messages", messages.count { it.getString("kind") == kind } >= minimum)
        }
        for (message in messages) {
            val id = message.getString("id")
            val text = message.getString("text")
            val words = text.trim().split(Regex("\\s+")).size
            when (message.getString("kind")) {
                "long_typed" -> assertTrue("$id must be 150–300 words", words in 150..300)
                "long_dictated", "dictated_bad_periods" ->
                    assertTrue("$id must be 150–400 words", words in 150..400)
                "very_long" -> assertTrue("$id must be over 450 words", words > 450)
            }
            if (message.getString("kind") == "long_dictated") {
                assertTrue("$id must contain no periods", '.' !in text)
            }
            if (message.getString("kind") in setOf("accidental_word", "nothing_relevant")) {
                assertEquals("$id must expect nothing", 0, message.getJSONArray("expect").length())
            }
            if (message.getString("kind") == "two_subjects") {
                assertEquals("$id must expect two memories", 2, message.getJSONArray("expect").length())
            }
            if (message.getString("kind") == "accidental_word") {
                val memory = memories.single { it.getString("id") == message.getString("shared_with") }
                val document = memory.getString("content") + "\n" +
                    memory.getJSONArray("tags").strings().joinToString(", ")
                val shared = KeywordText.terms(text).toSet().intersect(KeywordText.terms(document).toSet())
                assertEquals("$id must share exactly one informative word: $shared", 1, shared.size)
                assertTrue(message.getJSONArray("must_not").strings().contains(memory.getString("id")))
            }
        }

        fun vector(id: String): FloatArray {
            val array = vectors.getJSONArray(id)
            assertEquals("$id vector dimensions", 256, array.length())
            val result = FloatArray(array.length()) { array.getDouble(it).toFloat() }
            assertTrue("$id vector must be finite", result.all { it.isFinite() })
            val norm = result.sumOf { it.toDouble() * it.toDouble() }
            assertTrue("$id vector must have unit length", abs(norm - 1.0) < 0.001)
            return result
        }
        val corpus = memories.map { memory ->
            val id = memory.getString("id")
            LiveRetrieval.Candidate(
                RetrievableMemory(
                    memoryId = id, scope = "global", content = memory.getString("content"),
                    embeddingText = null, importance = 0, createdAt = "2028-01-01T00:00:00Z",
                    worldId = null
                ),
                vector(id), memory.getJSONArray("tags").strings(), emptyList()
            )
        }
        val byMessage = linkedMapOf<String, Counts>()
        val byKind = linkedMapOf<String, Counts>()
        val outcomes = linkedMapOf<String, Outcome>()
        for (message in allMessages) {
            val id = message.getString("id")
            val kind = message.getString("kind")
            val expected = message.getJSONArray("expect").strings().toSet()
            val forbidden = message.getJSONArray("must_not").strings().toSet()
            assertTrue("$id has an unknown expected memory", memoryIds.containsAll(expected))
            assertTrue("$id has an unknown forbidden memory", memoryIds.containsAll(forbidden))
            assertTrue("$id has conflicting answers", expected.intersect(forbidden).isEmpty())
            // Each conversation turn is intentionally searched on its own:
            // no previous message is concatenated and no result is carried over.
            val sent = LiveRetrieval.search(
                message.getString("text"), vector(id), corpus, LiveRetrieval.Params(), limit = 5
            ).ranked.map { it.memory.memoryId }.toSet()
            val result = outcome(expected, sent)
            val counts = result.counts()
            outcomes[id] = result
            byMessage[id] = counts
            byKind[kind] = (byKind[kind] ?: Counts(0, 0, 0)) + counts
            val record = JSONObject().put("id", id).put("kind", kind)
                .put("found", JSONArray(result.found.sorted()))
                .put("missed", JSONArray(result.missed.sorted()))
                .put("wrongly_sent", JSONArray(result.incorrect.sorted()))
                .put("sent", JSONArray(sent.sorted()))
            println("RECALL SAMPLE $record")
        }
        val total = byMessage.values.fold(Counts(0, 0, 0)) { a, b -> a + b }
        for ((kind, counts) in byKind) println("RECALL KIND $kind ${counts.report()}")
        println("RECALL TOTAL ${total.report()}")

        // On the first run results are printed before this read. Record those
        // measured counts by hand; never invent a passing baseline here.
        val baselineStream = javaClass.classLoader!!.getResourceAsStream("recall_test_set/baseline.json")
        checkNotNull(baselineStream) { "The baseline is not recorded yet; record the printed sample results." }
        val baseline = baselineStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        val digest = MessageDigest.getInstance("SHA-256").digest(casesText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("Expected answers must stay fixed", baseline.getString("cases_sha256"), digest)
        val previousMessages = baseline.getJSONObject("by_message")
        val failures = mutableListOf<String>()
        for ((id, current) in outcomes) {
            val previous = previousMessages.optJSONObject(id)
            if (previous == null) {
                failures.add("$id: the baseline is not recorded yet")
                continue
            }
            val found = previous.getJSONArray("found").strings().toSet()
            val incorrect = previous.getJSONArray("wrongly_sent").strings().toSet()
            val expected = allMessages.single { it.getString("id") == id }
                .getJSONArray("expect").strings().toSet()
            assertTrue("$id baseline has an unexpected correct id", expected.containsAll(found))
            assertTrue("$id baseline mislabels an expected id as incorrect", incorrect.intersect(expected).isEmpty())
            assertTrue("$id baseline refers to unknown memories", memoryIds.containsAll(found + incorrect))
            failures.addAll(regressions(id, found, incorrect, current))
        }
        failures.addAll((previousMessages.keySet() - outcomes.keys).map { "$it is missing from the test set" })
        assertTrue(
            "Recall baseline check failed:\n${failures.joinToString("\n")}", failures.isEmpty()
        )
    }
}
