package org.teslasoft.assistant.preferences.memory

import android.os.Bundle
import android.graphics.Bitmap
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.View
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.runtime.OnnxRuntimeEnvironment
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.enforcer.Enforcer
import org.teslasoft.assistant.preferences.memory.librarian.*
import org.teslasoft.assistant.ui.activities.MemorySearchTestActivity
import java.io.File
import kotlin.math.sqrt

/** Real encrypted-store -> Librarian -> Enforcer -> prompt boundary, using a
 * deterministic model to force the relevant states. No provider calls or
 * hardware performance claims. All substituted singletons are restored. */
@RunWith(AndroidJUnit4::class)
class Step2RecallInstrumentedTest {
    private val base: Context = ApplicationProvider.getApplicationContext()
    private val prefix = "step2_${System.nanoTime()}_"
    private val ctx = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(base.filesDir, prefix).apply { mkdirs() }
        override fun getDatabasePath(name: String): File = base.getDatabasePath(prefix + name)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            base.getSharedPreferences(prefix + name, mode)
    }
    private lateinit var store: MemoryStore
    private lateinit var librarian: Librarian
    private lateinit var enforcer: Enforcer
    private lateinit var fake: FakeModel
    private val oldSingletons = mutableListOf<Pair<java.lang.reflect.Field, Any?>>()
    private val whole = floatArrayOf(0f, 1f, 0f)
    private val section = floatArrayOf(1f, 0f, 0f)
    private val message = (1..180).joinToString(" ") { "filler$it" } +
        " juniper keeps a painted wooden telescope " + (181..280).joinToString(" ") { "filler$it" }

    private inner class FakeModel : EmbeddingModel {
        override val tag = "step2-test-3"
        override val dimensions = 3
        override val messageTokenLimit = 512
        override val storedTextTokenLimit = 2048
        var sectionCalls = 0
        var queryCalls = 0
        var failSections = false
        var failQuery = false
        override fun countTokens(text: String, isQuery: Boolean) = text.split(' ').size
        override fun embed(text: String, isQuery: Boolean, tokenLimit: Int, control: EmbeddingQueue.ReadControl?): FloatArray {
            if (text == message) {
                queryCalls++
                if (failQuery) error("test query failure")
                return whole
            }
            sectionCalls++
            if (failSections) error("test broken model")
            return if ("juniper" in text) section else floatArrayOf(0f, 0f, 1f)
        }
        override fun close() {}
    }

    private fun construct(type: Class<*>): Any = type.getDeclaredConstructor(Context::class.java).let {
        it.isAccessible = true; it.newInstance(ctx)
    }
    private fun singleton(type: Class<*>, value: Any) {
        val field = type.getDeclaredField("instance").apply { isAccessible = true }
        oldSingletons.add(field to field.get(null))
        field.set(null, value)
    }
    private fun model(value: EmbeddingModel?) {
        Librarian::class.java.getDeclaredField("model").apply { isAccessible = true }.set(librarian, value)
        Librarian::class.java.getDeclaredField("modelLoadFailed").apply { isAccessible = true }.setBoolean(librarian, value == null)
    }

    @Before fun setup() {
        store = MemoryStore.openForTest(ctx, MemoryStore.DATABASE_NAME, "step2-test-key".toByteArray())
        // openForTest uses ctx's path too; isProvisioned must address that same file.
        store.writableDatabase
        singleton(MemoryStore::class.java, store)
        librarian = construct(Librarian::class.java) as Librarian
        fake = FakeModel()
        model(fake)
        singleton(Librarian::class.java, librarian)
        enforcer = construct(Enforcer::class.java) as Enforcer
        store.setRetrievalPolicyJson("""{"top_k":1,"memory_char_budget":12000}""")
        Preferences.getPreferences(ctx, "").setUseImportanceRatings(true)
        Preferences.getPreferences(ctx, "").setMemoryDebugLogging(true)
        add("section", "juniper keeps a painted wooden telescope", sectionVector(.55), 3)
        for (i in 1..10) add("noise$i", "unrelated receipt number $i", floatArrayOf(0f, 0f, 1f))
    }

    @After fun cleanup() {
        oldSingletons.asReversed().forEach { (field, old) -> field.set(null, old) }
        if (::store.isInitialized) store.close()
        base.databaseList().filter { it.startsWith(prefix) }.forEach { base.deleteDatabase(it) }
        ctx.filesDir.deleteRecursively()
    }

    private fun sectionVector(sim: Double) = floatArrayOf(sim.toFloat(), 0f, sqrt(1 - sim * sim).toFloat())
    private fun add(id: String, text: String, vector: FloatArray, importance: Int = 0) {
        store.insertMemory(MemoryRecord(id, "global", text, null, "[]", importance,
            emptyList(), emptyList(), emptyList(), emptyList(), null, "[]", "2026-01-01T00:00:00Z",
            null, "active", null, emptyList(), emptyList(), emptyList()))
        store.upsertEmbedding(id, RetrievalDocument.effectiveKey(fake.tag), VectorMath.toBlob(vector))
    }
    private fun send(manual: List<String> = emptyList()) = enforcer.assembleTurnWithAttribution(
        Enforcer.TurnInput("step2-chat", "", message, "", "test", emptyList(), null, null, null, null, null,
            manualMemoryIds = manual))

    @Test fun sectionMemoryReachesFinalPromptAndReportAgrees() {
        val output = send()!!
        assertEquals(listOf("section"), output.memoryIds)
        assertTrue(output.prompt.contains("juniper keeps a painted wooden telescope"))
        assertFalse(output.prompt.contains("filler180"))
        assertEquals(LongMessageSearch.cut(message).size, fake.sectionCalls)
        assertEquals(1, fake.queryCalls)
        val prepared = librarian.prepareSearchTest(RetrievalScope(null, null, null, null), message)!!
        val report = SearchTestReport.build(message, prepared.queryVector, prepared.corpus,
            SearchTuning(maxMemories = 1), true, sections = prepared.sections)
        assertEquals(output.memoryIds, report.sent.map { it.explanation.memory.memoryId })
    }

    @Test fun wholeMatchAndManualAttachmentPreventSectionPlusThreeOverflow() {
        add("whole", "the primary whole message memory", whole)
        assertEquals(listOf("whole"), send()!!.memoryIds)
        assertEquals(listOf("whole"), send(listOf("whole"))!!.memoryIds)
    }

    @Test fun brokenSectionsPreserveWholeMatchesAndStopRepeatedWork() {
        add("whole", "the primary whole message memory", whole)
        fake.failSections = true
        assertEquals(listOf("whole"), send()!!.memoryIds)
        assertEquals(2, fake.sectionCalls)
        assertEquals(1, Regex("test broken model").findAll(Logger.getMemoryLog(ctx)).count())
    }

    @Test fun queryFailureAndUnavailableModelStillUseKeywords() {
        add("whole", message, whole)
        fake.failQuery = true
        assertTrue(send()!!.memoryIds.contains("whole"))
        assertEquals(0, fake.sectionCalls)
        model(null)
        assertTrue(send()!!.memoryIds.contains("whole"))
        assertEquals(0, fake.sectionCalls)
    }

    @Test fun assistantErrorsAndIncompleteIndexDescribeActualBackgroundOperation() {
        fake.failQuery = true
        librarian.searchForReconciliation(RetrievalScope(null, null, null, null), listOf(message), 5)
        val errorLog = Logger.getMemoryLog(ctx)
        assertTrue(errorLog.contains("Memory Assistant could not process its background search"))
        assertFalse(errorLog.contains("could not read this message"))
        assertEquals(0, fake.sectionCalls)
        store.writableDatabase.execSQL("DELETE FROM embeddings")
        librarian.searchForReconciliation(RetrievalScope(null, null, null, null), listOf("juniper"), 5)
        val incompleteLog = Logger.getMemoryLog(ctx)
        assertTrue(incompleteLog.contains("Memory Assistant searched the complete eligible set by keyword instead"))
        // No installed catalog model exists in this isolated context, so the
        // repair scheduler returns false and must not claim preparation.
        assertFalse(incompleteLog.contains("The app is preparing them in the background"))
    }

    @Test fun onnxEnvironmentAppliesTelemetryOptOutBeforeSessionUse() {
        // Executes the actual Android JNI opt-out, not a mocked method.
        assertSame(OnnxRuntimeEnvironment.get(), OnnxRuntimeEnvironment.get())
    }

    @Test fun realAndroidModelReferenceAndMeasuredSectionLatency() {
        // CI supplies temporary q4 artifacts and runs this offline. Local
        // instrumentation can omit the argument to run only deterministic
        // integration/UI checks without downloading a model.
        assumeTrue(InstrumentationRegistry.getArguments().getString("step2_real_model") == "true")
        val supplied = File(base.getExternalFilesDir(null), "step2-model")
        val runtimeContext = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(base.filesDir, "step2-runtime-$prefix").apply { mkdirs() }
        }
        val installed = EmbeddingModelStorage.modelDir(runtimeContext, EmbeddingModels.GEMMA_Q4)
        for (name in listOf("model.onnx", "model_q4.onnx_data", "tokenizer.json")) {
            val source = File(supplied, name)
            check(source.isFile) { "Supply $name in the target app's external step2-model directory" }
            source.copyTo(File(installed, name), overwrite = true)
        }
        val real = OnnxEmbeddingModel.create(runtimeContext, EmbeddingModels.GEMMA_Q4)
        try {
            verifyAndroidVectorsAndDecisions(real, "recall_test_set")
            verifyAndroidVectorsAndDecisions(real, "recall_independent_set")
            val reference = VectorMath.cosine(
                real.embed("What should I feed Pepper?", true),
                real.embed("Pepper is the user's cat. She has kidney disease and eats a prescription diet.", false))
            assertEquals(.6208, reference.toDouble(), .01)
            for (memory in store.allActiveMemories()) {
                val vector = real.embed(memory.content, false, real.storedTextTokenLimit)
                store.upsertEmbedding(memory.memoryId, RetrievalDocument.effectiveKey(real.tag), VectorMath.toBlob(vector))
            }
            model(real)
            val prepared = librarian.prepareSearchTest(RetrievalScope(null, null, null, null), message)!!
            val report = SearchTestReport.build(message, prepared.queryVector, prepared.corpus,
                SearchTuning(maxMemories = 1), true, sections = prepared.sections)
            val reading = report.sections
            assertEquals(256, prepared.queryVector!!.size)
            assertTrue(reading.vectors.values.all { it.size == 256 })
            val metrics = Bundle().apply {
                putString("step2_android_measurement", "ORT=${OnnxRuntimeEnvironment.get().version}; " +
                    "model=${real.tag}; reference=$reference; whole_model_ms=${prepared.embedMillis}; " +
                    "section_queue_ms=${reading.queueMillis}; section_model_ms=${reading.modelMillis}; " +
                    "section_scoring_ms=${reading.scoringMillis}; section_total_ms=${reading.millis}; attempted=${reading.checked}; " +
                    "successful=${reading.vectors.size}; timed_out=${reading.timedOut}")
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, metrics)
        } finally { model(fake); real.close(); runtimeContext.filesDir.deleteRecursively() }
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONArray.vector() = FloatArray(length()) { getDouble(it).toFloat() }

    /** Compare actual Android JNI outputs and retrieval decisions with the
     * Python fixture pipeline. Gold answers remain unchanged. */
    private fun verifyAndroidVectorsAndDecisions(real: EmbeddingModel, folder: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun fixture(name: String) = JSONObject(instrumentation.context.assets
            .open("$folder/$name").bufferedReader().use { it.readText() })
        val cases = fixture("cases.json")
        val vectors = fixture("vectors.json")
        val sections = fixture("section_vectors.json").getJSONObject("messages")
        val queue = EmbeddingQueue()
        val cache = HashMap<Pair<String, Boolean>, FloatArray>()
        var compared = 0
        var largestDifference = 0f
        var minimumCosine = 1f
        fun compare(text: String, query: Boolean, reference: FloatArray): FloatArray {
            val actual = cache.getOrPut(text to query) {
                queue.run(EmbeddingQueue.Priority.LIVE) { control ->
                    real.embed(text, query, if (query) real.messageTokenLimit else real.storedTextTokenLimit, control)
                }
            }
            assertEquals(reference.size, actual.size)
            assertTrue(actual.all { it.isFinite() })
            minimumCosine = minOf(minimumCosine, VectorMath.cosine(actual, reference))
            for (i in actual.indices) largestDifference = maxOf(largestDifference, abs(actual[i] - reference[i]))
            compared++
            return actual
        }
        val pythonCorpus = cases.getJSONArray("memories").objects().map { m ->
            LiveRetrieval.Candidate(RetrievableMemory(m.getString("id"), "global", m.getString("content"),
                null, 0, "2026-01-01T00:00:00Z", null), vectors.getJSONArray(m.getString("id")).vector(),
                m.getJSONArray("tags").strings(), emptyList())
        }
        val androidCorpus = pythonCorpus.map { c -> c.copy(vector = compare(
            RetrievalDocument.semanticDocument(c.memory.content, null, c.tags), false, c.vector!!)) }
        val pythonIndex = LiveRetrieval.index(pythonCorpus)
        val androidIndex = LiveRetrieval.index(androidCorpus)
        val messages = cases.getJSONArray("messages").objects() + cases.getJSONArray("conversations")
            .objects().flatMap { it.getJSONArray("messages").objects() }
        var correct = 0; var missed = 0; var incorrect = 0
        for (m in messages) {
            val id = m.getString("id")
            val query = m.getString("text")
            val expected = m.getJSONArray("expect").strings().toSet()
            val pythonQuery = vectors.getJSONArray(id).vector()
            val androidQuery = compare(query, true, pythonQuery)
            val recorded = sections.optJSONArray(id)?.objects().orEmpty()
            val androidSections = recorded.associate { s -> s.getString("text") to
                compare(s.getString("text"), true, s.getJSONArray("vector").vector()) }
            val pythonSections = recorded.associate { it.getString("text") to it.getJSONArray("vector").vector() }
            fun search(index: LiveRetrieval.Index, vector: FloatArray, sectionVectors: Map<String, FloatArray>) =
                SavedMemorySearch.search(query, vector, index, LiveRetrieval.Params(), 5, true) {
                    LongMessageSearch.read(query, index, now = { 0L }) { sectionVectors.getValue(it) }
                }
            val python = search(pythonIndex, pythonQuery, pythonSections)
            val android = search(androidIndex, androidQuery, androidSections)
            assertEquals("$folder/$id whole-message runtime difference",
                python.whole.ranked.map { it.memory.memoryId }, android.whole.ranked.map { it.memory.memoryId })
            assertEquals("$folder/$id section-retrieval runtime difference",
                python.ranked.map { it.memory.memoryId }, android.ranked.map { it.memory.memoryId })
            val sent = android.ranked.map { it.memory.memoryId }.toSet()
            correct += (sent intersect expected).size
            missed += (expected - sent).size
            incorrect += (sent - expected).size
        }
        // Fixture rounding and different native kernels need not be bitwise
        // identical; the separate exact decision assertions above are binding.
        assertTrue("$folder vectors diverged from the fixture model", minimumCosine >= .99f)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("step2_android_vector_parity", "$folder; compared=$compared; " +
                "max_abs_difference=$largestDifference; minimum_cosine=$minimumCosine; " +
                "correct=$correct; missed=$missed; incorrect=$incorrect")
        })
    }

    @Test fun resourcesRenderSingularPluralAndApprovedPlaceholders() {
        assertEquals("Additional section search: 1 section in 114 ms.",
            base.resources.getQuantityString(R.plurals.memory_search_test_sections_timing, 1, 1, 114))
        assertEquals("Additional section search: 3 sections in 320 ms.",
            base.resources.getQuantityString(R.plurals.memory_search_test_sections_timing, 3, 3, 320))
        assertTrue(base.getString(R.string.memory_search_test_detail_section, 2, 4, "opening words").contains("section 2 of 4"))
    }

    @Test fun screenRendersSectionValuesFailuresAndNoSectionState() {
        val prepared = librarian.prepareSearchTest(RetrievalScope(null, null, null, null), message)!!
        val report = SearchTestReport.build(message, prepared.queryVector, prepared.corpus,
            SearchTuning(), true, sections = prepared.sections)
        ActivityScenario.launch(MemorySearchTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val render = MemorySearchTestActivity::class.java.getDeclaredMethod("showReport",
                    Librarian.PreparedSearchTest::class.java, SearchTestReport.Report::class.java).apply { isAccessible = true }
                activity.findViewById<View>(R.id.section_result).visibility = View.VISIBLE
                render.invoke(activity, prepared, report)
                val list = activity.findViewById<LinearLayout>(R.id.list_sent)
                assertEquals(1, list.childCount)
                val meaning = list.getChildAt(0).findViewById<TextView>(R.id.text_meaning).text.toString()
                assertTrue(meaning.contains("required 0.48"))
                assertTrue(meaning.contains("0.55"))
                assertTrue(list.getChildAt(0).findViewById<TextView>(R.id.text_section).text.contains("Match found"))
                activity.findViewById<ScrollView>(R.id.scroll).post {
                    activity.findViewById<ScrollView>(R.id.scroll).scrollTo(0,
                        activity.findViewById<View>(R.id.section_result).top + activity.findViewById<View>(R.id.header_sent).top)
                }
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(base.getExternalFilesDir(null), "step2-match.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            scenario.onActivity { activity ->
                val render = MemorySearchTestActivity::class.java.getDeclaredMethod("showReport",
                    Librarian.PreparedSearchTest::class.java, SearchTestReport.Report::class.java).apply { isAccessible = true }
                val nearCorpus = prepared.corpus.map { if (it.memory.memoryId == "section")
                    it.copy(vector = sectionVector(.47)) else it }
                val near = SearchTestReport.build(message, whole, nearCorpus, SearchTuning(), true, sections = prepared.sections)
                render.invoke(activity, prepared.copy(corpus = nearCorpus), near)
                val nearCard = activity.findViewById<LinearLayout>(R.id.list_not_sent).getChildAt(0)
                assertTrue(nearCard.findViewById<TextView>(R.id.text_meaning).text.contains("0.47"))
                assertTrue(nearCard.findViewById<TextView>(R.id.text_outcome).text.contains(
                    activity.getString(R.string.memory_search_test_reject_similarity)))
                val roundedCorpus = nearCorpus.map { if (it.memory.memoryId == "section")
                    it.copy(vector = sectionVector(.479)) else it }
                val rounded = SearchTestReport.build(message, whole, roundedCorpus, SearchTuning(), true, sections = prepared.sections)
                render.invoke(activity, prepared.copy(corpus = roundedCorpus), rounded)
                val roundedCard = activity.findViewById<LinearLayout>(R.id.list_not_sent).getChildAt(0)
                assertTrue(roundedCard.findViewById<TextView>(R.id.text_meaning).text.contains("0.479"))
                val noOverlapCorpus = prepared.corpus.map { if (it.memory.memoryId == "section")
                    it.copy(memory = it.memory.copy(content = "The garden latch is stiff")) else it }
                val noOverlap = SearchTestReport.build(message, whole, noOverlapCorpus, SearchTuning(), true, sections = prepared.sections)
                render.invoke(activity, prepared.copy(corpus = noOverlapCorpus), noOverlap)
                val noOverlapCard = activity.findViewById<LinearLayout>(R.id.list_not_sent).getChildAt(0)
                assertTrue(noOverlapCard.findViewById<TextView>(R.id.text_outcome).text.contains(
                    activity.getString(R.string.memory_search_test_reject_words)))
                val failure = prepared.sections.copy(vectors = emptyMap(),
                    failures = listOf(LongMessageSearch.Failure(prepared.sections.sections.first(), "test model failure")), checked = 1)
                val failedReport = SearchTestReport.build(message, whole, prepared.corpus, SearchTuning(), true, sections = failure)
                render.invoke(activity, prepared.copy(sections = failure), failedReport)
                assertTrue(activity.findViewById<TextView>(R.id.text_section_search).text.contains("test model failure"))
                render.invoke(activity, prepared.copy(sections = LongMessageSearch.Reading.NONE),
                    SearchTestReport.build(message, whole, prepared.corpus, SearchTuning(), true))
                assertEquals(View.GONE, activity.findViewById<View>(R.id.text_section_search).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.text_section_timing).visibility)
            }
        }
    }
}
