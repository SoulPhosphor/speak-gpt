package org.teslasoft.assistant.preferences.memory.librarian

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.RetrievableMemory
import java.security.MessageDigest
import kotlin.math.sqrt

/** Replays measured Android cosine scores through the real Kotlin matcher.
 * No embedding inference or latency claim. Correctness comes exclusively
 * from the unchanged original cases/baseline, never native observations. */
class NativeMemoryGateRegressionTest {
    private fun resource(name: String) = checkNotNull(javaClass.classLoader!!
        .getResourceAsStream("recall_test_set/$name")).bufferedReader().use { it.readText() }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test fun nativeScoresPreserveOriginalCorrectMatchesWithoutNewIncorrectMatches() {
        val casesText = resource("cases.json")
        val cases = JSONObject(casesText)
        val baseline = JSONObject(resource("baseline.json"))
        val measuredText = resource("android_ort120_similarity_scores.json")
        assertEquals("Measured Android inputs changed", "329ae241df535863a09443964e3fab141661b4b47b1d11a082c2f35fd09a884a", hash(measuredText))
        val measured = JSONObject(measuredText)
        assertEquals(baseline.getString("cases_sha256"), hash(casesText))
        assertEquals(hash(casesText), measured.getString("cases_sha256"))
        assertEquals("1.20.0", measured.getString("runtime"))
        assertEquals("x86_64", measured.getString("abi"))
        assertEquals("ad1dfee81a70f7944b9b9d1cc6e48075b832881cf33fab2f2b248be78f3f0043", measured.getString("model_sha256"))
        val memories = cases.getJSONArray("memories").objects()
        assertEquals(memories.map { it.getString("id") }, measured.getJSONArray("memory_ids").strings())
        val messages = cases.getJSONArray("messages").objects() + cases.getJSONArray("conversations")
            .objects().flatMap { it.getJSONArray("messages").objects() }
        assertEquals(messages.map { it.getString("id") }.toSet(), measured.getJSONObject("messages").keySet())
        val query = floatArrayOf(1f, 0f)
        var correct = 0
        var incorrect = 0
        for (message in messages) {
            val id = message.getString("id")
            val scores = measured.getJSONObject("messages").getJSONArray(id)
            assertEquals(memories.size, scores.length())
            val corpus = memories.mapIndexed { position, memory ->
                val similarity = scores.getDouble(position).toFloat()
                assertTrue(similarity.isFinite() && similarity in -1f..1f)
                // Two-dimensional unit vectors reproduce this measured scalar
                // cosine. No new model-generated vector is being fabricated.
                val vector = floatArrayOf(similarity, sqrt(1.0 - similarity.toDouble() * similarity).toFloat())
                assertEquals(similarity.toDouble(), VectorMath.cosine(query, vector).toDouble(), 1e-6)
                LiveRetrieval.Candidate(RetrievableMemory(memory.getString("id"), "global", memory.getString("content"),
                    null, 0, "2026-01-01T00:00:00Z", null), vector, memory.getJSONArray("tags").strings(), emptyList())
            }
            val sent = LiveRetrieval.search(message.getString("text"), query, corpus,
                LiveRetrieval.Params(), 5).ranked.map { it.memory.memoryId }.toSet()
            val expected = message.getJSONArray("expect").strings().toSet()
            val previous = baseline.getJSONObject("by_message").getJSONObject(id)
            val required = previous.getJSONArray("found").strings().toSet() -
                previous.optJSONArray("found_by_section")?.strings()?.toSet().orEmpty()
            val allowedIncorrect = previous.getJSONArray("wrongly_sent").strings().toSet()
            assertTrue("$id lost a correct whole-message match: ${required - sent}", (required - sent).isEmpty())
            assertTrue("$id added incorrect matches: ${(sent - expected) - allowedIncorrect}",
                ((sent - expected) - allowedIncorrect).isEmpty())
            correct += (sent intersect expected).size
            incorrect += (sent - expected).size
        }
        assertTrue("Lost original correct whole-message matches", correct >= 54)
        assertTrue("Exceeded the original incorrect-match target", incorrect <= 19)
    }
}
