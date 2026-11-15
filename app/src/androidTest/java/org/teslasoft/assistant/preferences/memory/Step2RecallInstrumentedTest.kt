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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.*
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
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    private val runtimeModelDirs = mutableListOf<File>()
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
        var queryStarted: CountDownLatch? = null
        var queryRelease: CountDownLatch? = null
        override fun countTokens(text: String, isQuery: Boolean) = text.split(' ').size
        override fun embed(text: String, isQuery: Boolean, tokenLimit: Int, control: EmbeddingQueue.ReadControl?): FloatArray {
            if (text == message) {
                queryCalls++
                queryStarted?.countDown()
                queryRelease?.let { check(it.await(10, TimeUnit.SECONDS)) { "Test query was not released" } }
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
        runtimeModelDirs.forEach { it.deleteRecursively() }
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

    private fun logSince(before: String): String {
        val current = Logger.getMemoryLog(ctx)
        assertTrue("Memory log unexpectedly truncated during one operation", current.startsWith(before))
        return current.substring(before.length)
    }

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

    @Test fun sectionPlusThreeIsSentPastMaximumMemoriesAfterWholeMatchOrManualAttachment() {
        // Maximum Memories is 1 and "section" is rated +3. The whole-message
        // match, or the attached memory, takes the one normal place; the
        // matching +3 memory a section found is still sent, after it.
        add("whole", "the primary whole message memory", whole)
        assertEquals(listOf("whole", "section"), send()!!.memoryIds)
        assertEquals(listOf("whole", "section"), send(listOf("whole"))!!.memoryIds)
    }

    @Test fun sectionPlusTwoDoesNotGoPastMaximumMemories() {
        add("whole", "the primary whole message memory", whole)
        store.writableDatabase.execSQL("UPDATE memories SET importance = 2 WHERE memory_id = 'section'")
        assertEquals(listOf("whole"), send()!!.memoryIds)
    }

    @Test fun brokenSectionsPreserveWholeMatchesAndStopRepeatedWork() {
        add("whole", "the primary whole message memory", whole)
        fake.failSections = true
        val before = Logger.getMemoryLog(ctx)
        assertEquals(listOf("whole"), send()!!.memoryIds)
        assertEquals(2, fake.sectionCalls)
        assertEquals(1, Regex("test broken model").findAll(logSince(before)).count())
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
        val before = Logger.getMemoryLog(ctx)
        librarian.searchForReconciliation(RetrievalScope(null, null, null, null), listOf(message), 5)
        val errorLog = logSince(before)
        assertTrue(errorLog.contains("Memory Assistant could not process its background search"))
        assertFalse(errorLog.contains("could not read this message"))
        assertEquals(0, fake.sectionCalls)
        store.writableDatabase.execSQL("DELETE FROM embeddings")
        val beforeIncomplete = Logger.getMemoryLog(ctx)
        librarian.searchForReconciliation(RetrievalScope(null, null, null, null), listOf("juniper"), 5)
        val incompleteLog = logSince(beforeIncomplete)
        assertTrue(incompleteLog.contains("Memory Assistant searched the complete eligible set by keyword instead"))
        // No installed catalog model exists in this isolated context, so the
        // repair scheduler returns false and must not claim preparation.
        assertFalse(incompleteLog.contains("The app is preparing them in the background"))
    }

    @Test fun onnxEnvironmentAppliesTelemetryOptOutBeforeSessionUse() {
        // Executes the actual Android JNI opt-out, not a mocked method.
        assertSame(OnnxRuntimeEnvironment.get(), OnnxRuntimeEnvironment.get())
    }

    @Test fun calibratedControlsAndRejectedStandoutKeepTheirActualPrecision() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun fixture(name: String) = JSONObject(instrumentation.context.assets.open("recall_test_set/$name")
            .bufferedReader().use { it.readText() })
        val cases = fixture("cases.json")
        val measured = fixture("android_ort120_similarity_scores.json")
        val id = "conversation_06_turn_2"
        val messageCase = cases.getJSONArray("conversations").objects()
            .flatMap { it.getJSONArray("messages").objects() }.single { it.getString("id") == id }
        val ids = measured.getJSONArray("memory_ids").strings()
        val scores = measured.getJSONObject("messages").getJSONArray(id)
        val corpus = cases.getJSONArray("memories").objects().map { memory ->
            val sim = scores.getDouble(ids.indexOf(memory.getString("id"))).toFloat()
            LiveRetrieval.Candidate(RetrievableMemory(memory.getString("id"), "global", memory.getString("content"),
                null, 0, "2026-01-01T00:00:00Z", null), floatArrayOf(sim, sqrt(1.0 - sim.toDouble() * sim).toFloat()),
                memory.getJSONArray("tags").strings(), emptyList())
        }
        val query = messageCase.getString("text")
        val vector = floatArrayOf(1f, 0f)
        val prepared = Librarian.PreparedSearchTest(query, vector, corpus, true, 0, 0)
        val report = SearchTestReport.build(query, vector, corpus, SearchTuning(), true)
        assertFalse(report.sent.any { it.explanation.memory.memoryId == "food_bread" })
        ActivityScenario.launch(MemorySearchTestActivity::class.java).use { scenario ->
            var ready = false
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!ready && System.nanoTime() < until) {
                scenario.onActivity { activity ->
                    val control = activity.findViewById<org.teslasoft.assistant.ui.widgets.SamplingParameterControl>(R.id.slider_min_spread)
                    ready = kotlin.math.abs(control.value - 0.034f) < 1e-6
                }
                if (!ready) Thread.sleep(10)
            }
            assertTrue("Minimum spread was rounded while loading the screen", ready)
            scenario.onActivity { activity ->
                val control = activity.findViewById<org.teslasoft.assistant.ui.widgets.SamplingParameterControl>(R.id.slider_min_spread)
                assertEquals(0.034f, control.value, 1e-6f)
                val render = MemorySearchTestActivity::class.java.getDeclaredMethod("showReport",
                    Librarian.PreparedSearchTest::class.java, SearchTestReport.Report::class.java).apply { isAccessible = true }
                render.invoke(activity, prepared, report)
                val cards = activity.findViewById<LinearLayout>(R.id.list_not_sent)
                val bread = (0 until cards.childCount).map { cards.getChildAt(it) }.single {
                    it.findViewById<TextView>(R.id.text_memory).text.toString() == corpus.single { c -> c.memory.memoryId == "food_bread" }.memory.content }
                val text = bread.findViewById<TextView>(R.id.text_meaning).text.toString()
                assertTrue("Rejected score rounded to the passing threshold: $text",
                    text.contains(String.format(java.util.Locale.getDefault(), "%.4f", 2.529728)))
                assertTrue("Actual threshold missing: $text",
                    text.contains(String.format(java.util.Locale.getDefault(), "%.2f", 2.53)))
            }
        }
    }

    @Test fun indexReadFailureRetainsKeywordResultsAndActualCause() {
        // Only this isolated test database is altered.
        store.writableDatabase.execSQL("DROP TABLE embeddings")
        val prepared = librarian.prepareSearchTest(RetrievalScope(null, null, null, null), "juniper")!!
        assertTrue(prepared.hasModel)
        assertTrue("Index error was discarded", !prepared.indexFailureCause.isNullOrBlank())
        assertNull(prepared.queryVector)
        val report = SearchTestReport.build("juniper", null, prepared.corpus,
            SearchTuning(maxMemories = 1), true, sections = prepared.sections)
        assertEquals(listOf("section"), report.sent.map { it.explanation.memory.memoryId })
        ActivityScenario.launch(MemorySearchTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val render = MemorySearchTestActivity::class.java.getDeclaredMethod("showReport",
                    Librarian.PreparedSearchTest::class.java, SearchTestReport.Report::class.java).apply { isAccessible = true }
                render.invoke(activity, prepared, report)
                assertEquals(activity.getString(R.string.memory_search_test_mode_index_failed, prepared.indexFailureCause),
                    activity.findViewById<TextView>(R.id.text_mode).text.toString())
            }
        }
    }

    @Test fun editedMessageCannotReceiveThePreviousTestsResult() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.queryStarted = started
        fake.queryRelease = release
        ActivityScenario.launch(MemorySearchTestActivity::class.java).use { scenario ->
            var jobs = emptyList<Job>()
            try {
                scenario.onActivity { activity ->
                    activity.findViewById<android.widget.EditText>(R.id.field_message).setText(message)
                    val scopeJob = activity.lifecycleScope.coroutineContext[Job]!!
                    val existingJobs = scopeJob.children.toSet()
                    MemorySearchTestActivity::class.java.getDeclaredMethod("runTest")
                        .apply { isAccessible = true }.invoke(activity)
                    jobs = scopeJob.children.filterNot { it in existingJobs }.toList()
                }
                assertTrue("The old query did not start", started.await(10, TimeUnit.SECONDS))
                scenario.onActivity { activity ->
                    activity.findViewById<android.widget.EditText>(R.id.field_message).setText("a different message")
                }
            } finally { release.countDown() }
            runBlocking { withTimeout(10_000) { jobs.joinAll() } }
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.section_result).visibility)
                assertNull(MemorySearchTestActivity::class.java.getDeclaredField("prepared")
                    .apply { isAccessible = true }.get(activity))
            }
        }
    }

    @Test fun matchingCompletionReappearsAfterWhitespaceOrRestoredMessageEdit() {
        for (restoreOriginal in listOf(false, true)) {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            fake.queryStarted = started
            fake.queryRelease = release
            val callsBefore = fake.queryCalls
            ActivityScenario.launch(MemorySearchTestActivity::class.java).use { scenario ->
                var jobs = emptyList<Job>()
                try {
                    scenario.onActivity { activity ->
                        activity.findViewById<android.widget.EditText>(R.id.field_message).setText(message)
                        val scopeJob = activity.lifecycleScope.coroutineContext[Job]!!
                        val existingJobs = scopeJob.children.toSet()
                        MemorySearchTestActivity::class.java.getDeclaredMethod("runTest")
                            .apply { isAccessible = true }.invoke(activity)
                        jobs = scopeJob.children.filterNot { it in existingJobs }.toList()
                    }
                    assertTrue("The query did not start", started.await(10, TimeUnit.SECONDS))
                    scenario.onActivity { activity ->
                        val field = activity.findViewById<android.widget.EditText>(R.id.field_message)
                        if (restoreOriginal) {
                            field.setText("a different message")
                            field.setText(message)
                        } else field.setText("$message ")
                        assertEquals(View.GONE, activity.findViewById<View>(R.id.section_result).visibility)
                    }
                } finally { release.countDown() }
                runBlocking { withTimeout(10_000) { jobs.joinAll() } }
                scenario.onActivity { activity ->
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.section_result).visibility)
                    val prepared = MemorySearchTestActivity::class.java.getDeclaredField("prepared")
                        .apply { isAccessible = true }.get(activity) as Librarian.PreparedSearchTest
                    assertEquals(message, prepared.query)
                    assertEquals(callsBefore + 1, fake.queryCalls)
                }
            }
        }
    }

    @Test fun realAndroidModelReferenceAndMeasuredSectionLatency() {
        // Full class runs require the pinned model fixtures. Missing inputs
        // must fail rather than silently skip the actual Android accuracy gate.
        val supplied = File(base.filesDir, "step2-model")
        val runtimeContext = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(base.filesDir, "step2-runtime-$prefix").apply { mkdirs() }
        }
        runtimeModelDirs.add(runtimeContext.filesDir)
        val installed = EmbeddingModelStorage.modelDir(runtimeContext, EmbeddingModels.GEMMA_Q4)
        val hashes = mapOf(
            "model.onnx" to "ad1dfee81a70f7944b9b9d1cc6e48075b832881cf33fab2f2b248be78f3f0043",
            "model_q4.onnx_data" to "599962c3143b040de2dd05e5975be3e9091dd067cacc6a8f7186e3203bab9e02",
            "tokenizer.json" to "4dda02faaf32bc91031dc8c88457ac272b00c1016cc679757d1c441b248b9c47")
        for ((name, expectedHash) in hashes) {
            val source = File(supplied, name)
            val target = File(installed, name)
            // CI provisions these files as the app UID with run-as, avoiding
            // scoped shared storage and its shell/app visibility differences.
            assertTrue("Missing or unreadable validation file $source", source.isFile && source.canRead())
            source.copyTo(target, overwrite = true)
            val digest = MessageDigest.getInstance("SHA-256")
            target.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            assertEquals("Validation file $source", expectedHash,
                digest.digest().joinToString("") { "%02x".format(it) })
        }
        val real = OnnxEmbeddingModel.create(runtimeContext, EmbeddingModels.GEMMA_Q4)
        try {
            val tokenizer = OnnxEmbeddingModel::class.java.getDeclaredField("tokenizer")
                .apply { isAccessible = true }.get(real) as HfTokenizer
            val accuracyFailures = verifyAndroidVectorsAndDecisions(real, tokenizer, "recall_test_set") +
                verifyAndroidVectorsAndDecisions(real, tokenizer, "recall_independent_set")
            recordAndroidSimilarities(real, "recall_carryover_set")
            recordAndroidSimilarities(real, "recall_carryover_holdout")
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
            // Gather both datasets and latency before failing. Runtime
            // observations stay separate from the unchanged correct answers.
            assertTrue(accuracyFailures.joinToString("\n"), accuracyFailures.isEmpty())
        } finally { model(fake); real.close(); runtimeContext.filesDir.deleteRecursively() }
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONArray.vector() = FloatArray(length()) { getDouble(it).toFloat() }

    /** Compare actual Android JNI outputs and retrieval decisions with the
     * Python fixture pipeline. Gold answers remain unchanged. */
    /**
     * Measurement only, for the carry-over evaluation set: reads every memory
     * and message of [folder] with the real model on this device and records
     * each message's similarity to each memory. It decides nothing and checks
     * no result; the evaluation scores these numbers on a desktop afterwards.
     */
    private fun recordAndroidSimilarities(real: EmbeddingModel, folder: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun fixtureText(name: String) = instrumentation.context.assets
            .open("$folder/$name").bufferedReader().use { it.readText() }
        val casesText = fixtureText("cases.json")
        val cases = JSONObject(casesText)
        val reference = JSONObject(fixtureText("vectors.json"))
        val sections = JSONObject(fixtureText("section_vectors.json")).getJSONObject("messages")
        val queue = EmbeddingQueue()
        var compared = 0
        var largestDifference = 0f
        var minimumCosine = 1f
        fun read(text: String, query: Boolean, expected: FloatArray): FloatArray {
            val limit = if (query) real.messageTokenLimit else real.storedTextTokenLimit
            val actual = queue.run(EmbeddingQueue.Priority.LIVE) { control -> real.embed(text, query, limit, control) }
            assertEquals(expected.size, actual.size)
            assertTrue(actual.all { it.isFinite() })
            minimumCosine = minOf(minimumCosine, VectorMath.cosine(actual, expected))
            for (i in actual.indices) largestDifference = maxOf(largestDifference, abs(actual[i] - expected[i]))
            compared++
            return actual
        }
        val memories = cases.getJSONArray("memories").objects()
        val memoryVectors = memories.map { m ->
            read(RetrievalDocument.semanticDocument(m.getString("content"), null, m.getJSONArray("tags").strings()),
                false, reference.getJSONArray(m.getString("id")).vector())
        }
        fun scores(vector: FloatArray) = JSONArray().apply {
            for (memory in memoryVectors) put(Math.round(VectorMath.cosine(vector, memory) * 1_000_000.0) / 1_000_000.0)
        }
        val messages = JSONObject()
        val sectionScores = JSONObject()
        for (turn in cases.getJSONArray("conversations").objects().flatMap { it.getJSONArray("messages").objects() }) {
            val id = turn.getString("id")
            messages.put(id, scores(read(turn.getString("text"), true, reference.getJSONArray(id).vector())))
            val recorded = sections.optJSONArray(id)?.objects().orEmpty()
            if (recorded.isNotEmpty()) sectionScores.put(id, JSONArray().apply {
                for (section in recorded) put(scores(read(section.getString("text"), true,
                    section.getJSONArray("vector").vector())))
            })
        }
        val casesHash = MessageDigest.getInstance("SHA-256").digest(casesText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        File(base.getExternalFilesDir(null), "step2-$folder-similarities.json").writeText(
            JSONObject().put("data_kind", "measured_android_cosines_not_expected_answers")
                .put("runtime", OnnxRuntimeEnvironment.get().version)
                .put("abi", android.os.Build.SUPPORTED_ABIS.first())
                .put("cases_sha256", casesHash)
                .put("memory_ids", JSONArray(memories.map { it.getString("id") }))
                .put("messages", messages).put("sections", sectionScores)
                .put("vector_reference", JSONObject().put("compared", compared)
                    .put("max_abs_difference", largestDifference.toDouble())
                    .put("minimum_cosine", minimumCosine.toDouble())).toString())
        instrumentation.sendStatus(0, Bundle().apply {
            putString("carryover_android_similarities", "$folder; compared=$compared; " +
                "max_abs_difference=$largestDifference; minimum_cosine=$minimumCosine")
        })
    }

    private fun verifyAndroidVectorsAndDecisions(
        real: EmbeddingModel, tokenizer: HfTokenizer, folder: String
    ): List<String> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun fixtureText(name: String) = instrumentation.context.assets
            .open("$folder/$name").bufferedReader().use { it.readText() }
        fun fixture(name: String) = JSONObject(fixtureText(name))
        val casesText = fixtureText("cases.json")
        val cases = JSONObject(casesText)
        val vectors = fixture("vectors.json")
        val sections = fixture("section_vectors.json").getJSONObject("messages")
        val tokenHashes = fixture("token_hashes.json").getJSONObject("hashes")
        // New observations are evidence, never permission to lower accuracy.
        val accuracyBaseline = fixture(if (folder == "recall_test_set") "baseline.json" else "observed.json")
        val baselineMessages = accuracyBaseline.getJSONObject("by_message")
        val casesHash = MessageDigest.getInstance("SHA-256").digest(casesText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("Correct-answer labels must stay fixed", accuracyBaseline.getString("cases_sha256"), casesHash)
        val failures = mutableListOf<String>()
        val runtimeDifferences = mutableListOf<String>()
        val inputDiagnostics = JSONArray()
        val decisionDiagnostics = JSONArray()
        val queue = EmbeddingQueue()
        val cache = HashMap<Pair<String, Boolean>, FloatArray>()
        var compared = 0
        var largestDifference = 0f
        var minimumCosine = 1f
        fun compare(label: String, text: String, query: Boolean, reference: FloatArray): FloatArray {
            val limit = if (query) real.messageTokenLimit else real.storedTextTokenLimit
            val prefix = if (query) EmbeddingModels.GEMMA_Q4.queryPrefix else EmbeddingModels.GEMMA_Q4.documentPrefix
            val ids = tokenizer.encode(prefix + text, limit)
            val tokenHash = MessageDigest.getInstance("SHA-256")
                .digest(ids.joinToString(",").toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val tokensMatch = tokenHash == tokenHashes.getString(label)
            if (!tokensMatch) failures.add("$folder/$label token IDs differ from the independent reference")
            val actual = cache.getOrPut(text to query) {
                queue.run(EmbeddingQueue.Priority.LIVE) { control ->
                    real.embed(text, query, limit, control)
                }
            }
            assertEquals(reference.size, actual.size)
            assertTrue(actual.all { it.isFinite() })
            val cosine = VectorMath.cosine(actual, reference)
            minimumCosine = minOf(minimumCosine, cosine)
            var maxDifference = 0f
            for (i in actual.indices) maxDifference = maxOf(maxDifference, abs(actual[i] - reference[i]))
            largestDifference = maxOf(largestDifference, maxDifference)
            inputDiagnostics.put(JSONObject().put("input", label).put("tokens_match", tokensMatch)
                .put("token_count", ids.size).put("actual_token_sha256", tokenHash)
                .put("expected_token_sha256", tokenHashes.getString(label))
                .put("cosine", cosine.toDouble()).put("max_abs_difference", maxDifference.toDouble()))
            compared++
            return actual
        }
        val pythonCorpus = cases.getJSONArray("memories").objects().map { m ->
            LiveRetrieval.Candidate(RetrievableMemory(m.getString("id"), "global", m.getString("content"),
                null, 0, "2026-01-01T00:00:00Z", null), vectors.getJSONArray(m.getString("id")).vector(),
                m.getJSONArray("tags").strings(), emptyList())
        }
        val androidCorpus = pythonCorpus.map { c -> c.copy(vector = compare(
            "memory:${c.memory.memoryId}", RetrievalDocument.semanticDocument(c.memory.content, null, c.tags), false, c.vector!!)) }
        val pythonIndex = LiveRetrieval.index(pythonCorpus)
        val androidIndex = LiveRetrieval.index(androidCorpus)
        val messages = cases.getJSONArray("messages").objects() + cases.getJSONArray("conversations")
            .objects().flatMap { it.getJSONArray("messages").objects() }
        var correct = 0; var missed = 0; var incorrect = 0
        var wholeCorrect = 0; var wholeMissed = 0; var wholeIncorrect = 0
        for (m in messages) {
            val id = m.getString("id")
            val query = m.getString("text")
            val expected = m.getJSONArray("expect").strings().toSet()
            val pythonQuery = vectors.getJSONArray(id).vector()
            val androidQuery = compare("query:$id", query, true, pythonQuery)
            val recorded = sections.optJSONArray(id)?.objects().orEmpty()
            val androidSections = recorded.mapIndexed { position, s -> s.getString("text") to
                compare("section:$id:$position", s.getString("text"), true, s.getJSONArray("vector").vector()) }.toMap()
            val pythonSections = recorded.associate { it.getString("text") to it.getJSONArray("vector").vector() }
            fun search(index: LiveRetrieval.Index, vector: FloatArray, sectionVectors: Map<String, FloatArray>) =
                SavedMemorySearch.search(query, vector, index, LiveRetrieval.Params(), 5, true) {
                    LongMessageSearch.read(query, index, now = { 0L }) { sectionVectors.getValue(it) }
                }
            val python = search(pythonIndex, pythonQuery, pythonSections)
            val android = search(androidIndex, androidQuery, androidSections)
            val pythonWhole = python.whole.ranked.map { it.memory.memoryId }
            val androidWhole = android.whole.ranked.map { it.memory.memoryId }
            val pythonSent = python.ranked.map { it.memory.memoryId }
            val androidSent = android.ranked.map { it.memory.memoryId }
            if (pythonWhole != androidWhole)
                runtimeDifferences.add("$folder/$id whole-message runtime difference: Python=$pythonWhole Android=$androidWhole")
            if (pythonSent != androidSent)
                runtimeDifferences.add("$folder/$id section-retrieval runtime difference: Python=$pythonSent Android=$androidSent")
            val prior = if (folder == "recall_test_set") baselineMessages.getJSONObject(id) else null
            val previousSent = if (prior == null) baselineMessages.getJSONArray(id).strings().toSet() else emptySet()
            val requiredCorrect = prior?.getJSONArray("found")?.strings()?.toSet() ?: (previousSent intersect expected)
            val previouslyIncorrect = prior?.getJSONArray("wrongly_sent")?.strings()?.toSet() ?: (previousSent - expected)
            assertTrue("$folder/$id baseline mislabels correct memories", requiredCorrect.all { it in expected })
            assertTrue("$folder/$id baseline mislabels incorrect memories", previouslyIncorrect.none { it in expected })
            val lostCorrect = requiredCorrect - androidSent.toSet()
            val addedIncorrect = (androidSent.toSet() - expected) - previouslyIncorrect
            if (lostCorrect.isNotEmpty()) failures.add("$folder/$id lost required correct memories: $lostCorrect")
            if (addedIncorrect.isNotEmpty()) failures.add("$folder/$id added incorrect memories against original baseline: $addedIncorrect")
            if (prior != null) {
                val previouslySectionOnly = prior.optJSONArray("found_by_section")?.strings()?.toSet().orEmpty()
                val lostWholeCorrect = (requiredCorrect - previouslySectionOnly) - androidWhole.toSet()
                val addedWholeIncorrect = (androidWhole.toSet() - expected) - previouslyIncorrect
                if (lostWholeCorrect.isNotEmpty()) failures.add("$folder/$id whole search lost required correct memories: $lostWholeCorrect")
                if (addedWholeIncorrect.isNotEmpty()) failures.add("$folder/$id whole search added incorrect memories: $addedWholeIncorrect")
            }
            if (androidSent.take(androidWhole.size) != androidWhole)
                failures.add("$folder/$id whole-message matches/order were not preserved")
            val newlyWrong = (androidSent.toSet() - expected) - (androidWhole.toSet() - expected)
            if (newlyWrong.isNotEmpty()) failures.add("$folder/$id sections added incorrect matches: $newlyWrong")
            val scores = JSONArray()
            val androidExplanations = android.whole.explanations.associateBy { it.memory.memoryId }
            for (p in python.whole.explanations) {
                val a = androidExplanations.getValue(p.memory.memoryId)
                scores.put(JSONObject().put("memory", p.memory.memoryId)
                    .put("python_similarity", p.similarity ?: JSONObject.NULL)
                    .put("android_similarity", a.similarity ?: JSONObject.NULL)
                    .put("python_passed", p.passed).put("android_passed", a.passed))
            }
            decisionDiagnostics.put(JSONObject().put("message", id)
                .put("python_whole", JSONArray(pythonWhole)).put("android_whole", JSONArray(androidWhole))
                .put("python_sent", JSONArray(pythonSent)).put("android_sent", JSONArray(androidSent))
                .put("whole_scores", scores))
            val sent = android.ranked.map { it.memory.memoryId }.toSet()
            val wholeSent = androidWhole.toSet()
            wholeCorrect += (wholeSent intersect expected).size
            wholeMissed += (expected - wholeSent).size
            wholeIncorrect += (wholeSent - expected).size
            correct += (sent intersect expected).size
            missed += (expected - sent).size
            incorrect += (sent - expected).size
        }
        // Original vector-similarity tolerance is unchanged. Runtime-specific
        // lists, whole-message preservation and no-new-wrong checks are above.
        if (minimumCosine < .99f) failures.add("$folder vectors diverged from the fixture model: minimum cosine=$minimumCosine")
        File(base.getExternalFilesDir(null), "step2-$folder-parity.json").writeText(
            JSONObject().put("inputs", inputDiagnostics).put("decisions", decisionDiagnostics)
                .put("runtime_differences", JSONArray(runtimeDifferences)).put("failures", JSONArray(failures))
                .put("accuracy_baseline", if (folder == "recall_test_set") "baseline.json" else "observed.json").put("correct", correct)
                .put("missed", missed).put("incorrect", incorrect).toString(2))
        instrumentation.sendStatus(0, Bundle().apply {
            putString("step2_android_vector_parity", "$folder; compared=$compared; " +
                "max_abs_difference=$largestDifference; minimum_cosine=$minimumCosine; " +
                "whole_correct=$wholeCorrect; whole_missed=$wholeMissed; whole_incorrect=$wholeIncorrect; " +
                "correct=$correct; missed=$missed; incorrect=$incorrect")
        })
        return failures
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
