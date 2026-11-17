package org.teslasoft.assistant.preferences.memory

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.memory.archivist.Archivist
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistResponseParser
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistSceneContext
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModels
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModelStorage
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingTokenCounter
import org.teslasoft.assistant.ui.widgets.MemoryTokenMeter
import java.io.File

/** Real filing/storage path. Synthetic installed tokenizer; no inference or provider calls. */
@RunWith(AndroidJUnit4::class)
class MemoryLengthInstrumentedTest {
    private val base: Context get() = ApplicationProvider.getApplicationContext()
    private val name = "memory_length_${System.nanoTime()}"
    private lateinit var context: Context
    private lateinit var store: MemoryStore

    @Before fun setup() {
        System.loadLibrary("sqlcipher")
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(base.filesDir, name).apply { mkdirs() }
        }
        store = MemoryStore.openForTest(context, "$name.db", "length-test-key".toByteArray())
        val model = EmbeddingModels.GEMMA_Q4
        EmbeddingModelStorage.modelFile(context, model).writeText("fixture; not used for inference")
        for (extra in model.extraFiles.filterNot { it.optional }) {
            EmbeddingModelStorage.extraFile(context, model, extra).writeText("fixture")
        }
        val vocab = JSONObject().put("<unk>", 0)
        for (code in 0..255) vocab.put("<0x%02X>".format(code), code + 1)
        EmbeddingModelStorage.tokenizerFile(context, model).writeText(JSONObject().apply {
            put("model", JSONObject().put("type", "BPE").put("unk_token", "<unk>")
                .put("byte_fallback", true).put("vocab", vocab).put("merges", JSONArray()))
        }.toString())
    }

    @After fun cleanup() {
        store.close()
        context.filesDir.deleteRecursively()
        base.deleteDatabase("$name.db")
    }

    private fun prepare(drafts: List<ArchivistResponseParser.DraftMemory>, cap: Int): Any {
        val method = Archivist::class.java.declaredMethods.single { it.name == "prepareMemoryDrafts" }
        method.isAccessible = true
        return method.invoke(Archivist, context, store,
            Archivist.Conversation("source-chat", "Example conversation", emptyList()), drafts, cap)!!
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(value: Any, name: String): T =
        value.javaClass.getDeclaredField(name).let { it.isAccessible = true; it.get(value) as T }

    @Test fun scannerFilesEveryPartWithTargetsTypeTagsHintsAndSourcesDespiteCapOne() {
        store.upsertProject(ProjectRecord("project", "Example", "active", MemoryStore.nowIso(), MemoryStore.nowIso()))
        val text = (1..300).joinToString("\n\n") { "Detail $it: the complete original wording stays here." }
        val draft = ArchivistResponseParser.DraftMemory(
            content = text, scope = "project", typeIdSuggestion = "mtype-fact",
            tags = listOf("detailed", "example"), targetName = null,
            targetIds = listOf("project"), scene = ArchivistSceneContext(null, null, null, null, "project")
        )
        val prepared = prepare(listOf(draft, draft.copy(content = "Second subject")), 1)
        val records: List<MemoryRecord> = field(prepared, "records")
        val sources: Map<String, List<MemorySource>> = field(prepared, "sourcesByMemoryId")
        assertTrue(records.size > 1)
        assertEquals(text, records.joinToString("") { it.content })
        assertEquals(records.size, records.map { it.memoryId }.distinct().size)
        val reader = EmbeddingTokenCounter.reader(context)!!
        for (record in records) {
            assertEquals("project", record.scope)
            assertEquals(listOf("project"), record.projectIds)
            assertEquals("mtype-fact", record.typeId)
            assertEquals(draft.tags, MemoryTokenMeter.input(record).tags)
            assertTrue(reader.measure(MemoryTokenMeter.input(record)).fits)
            assertEquals("source-chat", sources.getValue(record.memoryId).single().chatId)
        }
        store.commitRerunChatOutputs(records, emptyList(), emptyList(), sourcesByMemoryId = sources)
        assertEquals(text, records.joinToString("") { store.getMemory(it.memoryId)!!.content })
        // Re-running must not create the same parts again.
        val rerun = prepare(listOf(draft), 1)
        assertTrue(field<List<MemoryRecord>>(rerun, "records").isEmpty())
    }

    @Test fun countingAnExistingOversizedMemoryNeverChangesIt() {
        val candidate = MemoryCandidateValidator.validateGeneral(
            scope = "global", content = "Existing text. ".repeat(500), tags = listOf("tag")
        ) as CandidateResult.Valid
        val record = PendingMemoryRecordFactory.build(candidate.candidate, MemoryId.generate(MemoryId.Type.ASSOCIATIVE), MemoryStore.nowIso())
        store.insertMemory(record)
        val before = store.getMemory(record.memoryId)!!
        assertFalse(EmbeddingTokenCounter.reader(context)!!.measure(MemoryTokenMeter.input(before)).fits)
        assertEquals(before, store.getMemory(record.memoryId))
    }
}
