package org.teslasoft.assistant.preferences.memory.librarian

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.RetrievableMemory
import java.security.MessageDigest

/** Gold answers were frozen before generating these new q4 vectors. This is
 * independent evaluation of the fixed parameters, not another tuning set. */
class Step2IndependentModelEvaluationTest {
    private fun resource(name: String): String = checkNotNull(javaClass.classLoader!!
        .getResourceAsStream("recall_independent_set/$name")).bufferedReader().use { it.readText() }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONArray.vector() = FloatArray(length()) { getDouble(it).toFloat() }

    @Test fun fixedParametersAreEvaluatedWithoutChangingTheIndependentGoldAnswers() {
        val text = resource("cases.json")
        val cases = JSONObject(text)
        val vectors = JSONObject(resource("vectors.json"))
        val recordedSections = JSONObject(resource("section_vectors.json"))
        assertEquals(LongMessageSearch.Settings().sectionWords, recordedSections.getInt("section_words"))
        val corpus = cases.getJSONArray("memories").objects().map { m ->
            LiveRetrieval.Candidate(RetrievableMemory(m.getString("id"), "global", m.getString("content"),
                null, 0, "2026-01-01T00:00:00Z", null), vectors.getJSONArray(m.getString("id")).vector(),
                m.getJSONArray("tags").strings(), emptyList())
        }
        val index = LiveRetrieval.index(corpus)
        var found = 0; var missed = 0; var incorrect = 0
        var wholeFound = 0; var wholeMissed = 0; var wholeIncorrect = 0
        val observed = JSONObject()
        for (message in cases.getJSONArray("messages").objects()) {
            val id = message.getString("id")
            val query = message.getString("text")
            val expected = message.getJSONArray("expect").strings().toSet()
            val sections = recordedSections.getJSONObject("messages").optJSONArray(id)?.objects().orEmpty()
            assertEquals(sections.map { it.getString("text") }, LongMessageSearch.cut(query).map { it.text })
            val reading = LongMessageSearch.read(query, index, now = { 0L }) { section ->
                sections.single { it.getString("text") == section }.getJSONArray("vector").vector()
            }
            val result = SavedMemorySearch.search(query, vectors.getJSONArray(id).vector(), index,
                LiveRetrieval.Params(), 5, true) { reading }
            val whole = result.whole.ranked.map { it.memory.memoryId }.toSet()
            val sent = result.ranked.map { it.memory.memoryId }.toSet()
            found += (sent intersect expected).size; missed += (expected - sent).size; incorrect += (sent - expected).size
            wholeFound += (whole intersect expected).size; wholeMissed += (expected - whole).size
            wholeIncorrect += (whole - expected).size
            observed.put(id, JSONArray(result.ranked.map { it.memory.memoryId }))
            val report = SearchTestReport.build(query, vectors.getJSONArray(id).vector(), corpus,
                SearchTuning(maxMemories = 5), true, sections = reading)
            assertEquals("$id: report and live selection differ", result.ranked.map { it.memory.memoryId },
                report.sent.map { it.explanation.memory.memoryId })
            for (match in result.sectionMatches) {
                assertTrue(match.explanation.passed)
                assertTrue(match.explanation.similarity!! >= reading.settings.sectionSimilarityMin)
                assertTrue(match.explanation.matchedTerms.isNotEmpty())
            }
        }
        println("INDEPENDENT MODEL whole found=$wholeFound missed=$wholeMissed incorrect=$wholeIncorrect; sections found=$found missed=$missed incorrect=$incorrect")
        println("INDEPENDENT OBSERVED $observed")
        val baseline = JSONObject(resource("observed.json"))
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("Independent gold answers changed", baseline.getString("cases_sha256"), digest)
        val previous = baseline.getJSONObject("by_message")
        assertEquals(previous.keySet(), observed.keySet())
        for (id in observed.keySet()) assertEquals("$id: independent retrieval changed; inspect gold accuracy",
            previous.getJSONArray(id).strings(), observed.getJSONArray(id).strings())
    }
}
