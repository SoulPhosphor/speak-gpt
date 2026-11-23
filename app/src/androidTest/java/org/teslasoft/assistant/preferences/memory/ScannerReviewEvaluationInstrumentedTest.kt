package org.teslasoft.assistant.preferences.memory

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.memory.archivist.*
import org.teslasoft.assistant.preferences.memory.librarian.*
import org.teslasoft.assistant.preferences.memory.matching.*
import java.io.File
import org.teslasoft.assistant.preferences.memory.scan.ScanSourceRow
import kotlinx.coroutines.runBlocking

/** Normal-CI native evaluation: actual extraction fixture -> real EmbeddingGemma
 * comparison/cache -> filing plan -> encrypted Pending records -> exact pair ->
 * explicit review. Native timings are emulator measurements, never phone claims. */
@RunWith(AndroidJUnit4::class)
class ScannerReviewEvaluationInstrumentedTest {
    @Test fun actualEmbeddingColdWarmReuseAndScanToReviewWithAndWithoutEmbeddings() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val prefix="scanner_eval_${System.nanoTime()}"
        val runtime=object:ContextWrapper(context){
            override fun getApplicationContext():Context=this
            override fun getFilesDir():File=File(context.filesDir,prefix).apply{mkdirs()}
        }
        val installed=EmbeddingModelStorage.modelDir(runtime,EmbeddingModels.GEMMA_Q4).apply{mkdirs()}
        val supplied=File(context.filesDir,"step2-model")
        for(name in listOf("model.onnx","model_q4.onnx_data","tokenizer.json")) {
            val source=File(supplied,name)
            assertTrue("Missing CI model fixture $name",source.isFile)
            source.copyTo(File(installed,name),overwrite=true)
        }
        var model:OnnxEmbeddingModel?=null
        val databases=ArrayList<String>()
        try {
            model=OnnxEmbeddingModel.create(runtime,EmbeddingModels.GEMMA_Q4)
            for(embeddings in listOf(false,true)) {
                val name="${prefix}_$embeddings.db";databases.add(name)
                val store=MemoryStore.openForTest(context,name,"scanner-review-evaluation-key".toByteArray())
                try { evaluate(context,store,model,embeddings) } finally { store.close() }
            }
        } finally {
            model?.close();runtime.filesDir.deleteRecursively();databases.forEach{context.deleteDatabase(it)}
        }
    }
    private fun evaluate(context:Context,store:MemoryStore,model:EmbeddingModel,embeddings:Boolean) {
        val emptyProtocol=ArchivistRuntimeProtocol.create(ArchivistSceneContext(null,null,null,null,null),emptyList(),emptyList())
        val originalRaw=InstrumentationRegistry.getInstrumentation().context.assets.open("scanner_evaluation/short.response.json").bufferedReader().use{it.readText()}
        val original=ArchivistResponseParser.parse(originalRaw,emptyProtocol).memories.single()
        val oldText=original.content
        val old=record("old",oldText)
        store.commitScanRange(range(store,"first"),emptyList(),listOf(old),emptyList(),emptyList())
        val protocol=ArchivistRuntimeProtocol.create(ArchivistSceneContext(null,null,null,null,null),
            listOf(ArchivistExistingMemory("old",oldText,"real_life",emptyList(),"Fact","pending")),emptyList())
        val raw=InstrumentationRegistry.getInstrumentation().context.assets.open("scanner_evaluation/new-decision.response.json").bufferedReader().use{it.readText()}
        val repeatedRaw=InstrumentationRegistry.getInstrumentation().context.assets.open("scanner_evaluation/retelling.response.json").bufferedReader().use{it.readText()}
        val repeated=ArchivistResponseParser.parse(repeatedRaw,protocol)
        assertTrue(repeated.memories.isEmpty())
        val repeatedText="I volunteer at Cedar Observatory on Saturday mornings because choir occupies my weekday evenings."
        val repeatedRow=ScanSourceRow("second-chat","second-message",1L,"user",0,repeatedText.length,repeatedText,null,null)
        val covered=CoveredEntries.validate(repeated.alreadyCovered,protocol.memoryIdByAlias,listOf(repeatedRow)) {
            MemoryDependencies.of(old,MemoryStore.draftRejectionHash(old.content))
        }
        assertEquals(1,covered.size)
        store.commitScanRange(range(store,"second"),emptyList(),emptyList(),emptyList(),emptyList(),dependencies=covered.map{it.dependency})
        val update=ArchivistResponseParser.parse(raw,protocol).memories.single()
        val proposal=ComparisonRecord(null,1,MatchStatus.WORKING_DRAFT,update.content,update.content,"proposal-fp")
        val existing=ComparisonRecord("old",null,MatchStatus.PENDING,oldText,oldText,"saved-fp")
        val vectors=NativeVectors(store,model)
        val coldStart=System.nanoTime()
        val evidence=ScannerMatchEngine.lookupProposal(proposal,listOf(existing),update.relatedExistingMemoryIds,emptyList(),if(embeddings)vectors else null)
        val coldMs=(System.nanoTime()-coldStart)/1_000_000
        if(embeddings) {
            assertEquals(2,vectors.reads)
            assertEquals(1,ScannerComparisonCache.count(store.readableDatabase))
            val warmStart=System.nanoTime()
            ScannerMatchEngine.lookupProposal(proposal,listOf(existing),update.relatedExistingMemoryIds,emptyList(),vectors)
            val warmMs=(System.nanoTime()-warmStart)/1_000_000
            assertEquals("Warm lookup must reuse both document vectors",2,vectors.reads)
            println("scanner_native_evaluation cold_reads=2 warm_reads=0 cold_lookup_ms=$coldMs warm_lookup_ms=$warmMs method=${evidence.matches.firstOrNull()?.method}")
        } else assertTrue(evidence.matches.all{it.score==null})
        var comparisons=0;var rankings=0
        val plan=runBlocking{ScanFilingPlanner({if(it=="old")CurrentMemory(old,"wording-hash")else null},
            {_,_->"Sunday mornings from February; choir rules out weekday evenings."},
            {comparisons++;emptySet()},{_,_->rankings++;emptyList()}).plan(listOf(FilingDraft(
                1,update.content,MemoryMatch.placementKey("real_life",emptyList()),update.relatedExistingMemoryIds,
                setOf("old"),emptyList(),false,evidence.matches)),1)}
        assertEquals(0,comparisons);assertEquals(0,rankings);assertEquals(listOf(listOf(1)),plan.units)
        val range=range(store,"third")
        store.commitScanRange(range,emptyList(),listOf(record("new",update.content)),emptyList(),emptyList(),
            relationshipHintsByMemoryId=mapOf("new" to plan.memoryLinks.getValue(1)))
        val hints=store.relationshipHintsForDraft("new")
        assertEquals(listOf("old"),hints.map{it.memoryId})
        val displayed=PendingMatchPresentation.order(hints,{store.getMemory(it)?.status},{false})
        assertTrue(displayed.single().reviewOnly)
        val snapshot=store.capturePendingPair("new","old")!!
        assertTrue(store.resolvePendingPair(snapshot,PendingPairAction.KEEP_BOTH) is MemoryStore.ResolutionResult.Applied)
        assertTrue(store.resolvePendingPair(snapshot,PendingPairAction.SAVE,"new") is MemoryStore.ResolutionResult.Applied)
        assertEquals("active",store.getMemory("new")!!.status);assertEquals("draft",store.getMemory("old")!!.status)
    }
    private fun range(store:MemoryStore,label:String):ScanRangeRecord {
        val run=MemoryStore.newId("run-")
        val range=ScanRangeRecord(MemoryStore.newId("range-"),run,"synthetic-$label",0,ScanRangeRecord.KIND_RERUN,emptyList(),emptyList())
        store.beginScanRun(ArchivistRunRecord(runId=run,startedAt=MemoryStore.nowIso(),finishedAt=null,status="running",
            chatIdsJson="[]",transcriptIdsJson="[]",memoryIdsJson="[]",ruleIdsJson="[]",foundCount=0,failedChatIdsJson="[]",error=null),listOf(range))
        return range
    }
    private class NativeVectors(private val store:MemoryStore,private val model:EmbeddingModel):ComparisonVectors {
        override val modelKey=RetrievalDocument.effectiveKey(model.tag)
        private val drafts=HashMap<String,FloatArray>()
        private val queue=EmbeddingQueue()
        var reads=0
        override fun known(records:List<ComparisonRecord>):Map<String,FloatArray> {
            val filed=ScannerComparisonCache.filedVectors(store.readableDatabase,modelKey,
                records.mapNotNull{r->r.memoryId?.let{it to r.fingerprint}}.toMap())
            return records.mapNotNull{r->
                val vector=if(r.memoryId!=null)filed[r.memoryId]?.let(VectorMath::fromBlob)else drafts[r.key+r.fingerprint]
                vector?.let{r.key to it}
            }.toMap()
        }
        override fun generate(record:ComparisonRecord):FloatArray {
            reads++
            val vector=queue.run(EmbeddingQueue.Priority.BACKGROUND){control->model.embed(record.document,false,model.storedTextTokenLimit,control)}
            val id=record.memoryId
            if(id!=null)assertTrue(ScannerComparisonCache.putFiled(store.writableDatabase,id,modelKey,record.fingerprint,VectorMath.toBlob(vector)))
            else drafts[record.key+record.fingerprint]=vector
            return vector
        }
        override fun query(text:String)=queue.run(EmbeddingQueue.Priority.BACKGROUND){control->model.embed(text,true,model.messageTokenLimit,control)}
    }
    private fun record(id:String,text:String)=MemoryRecord(id,"real_life",text,null,"[]",0,emptyList(),emptyList(),emptyList(),emptyList(),null,"[]","2026-01-01T00:00:00Z",null,"draft",null,emptyList(),emptyList(),emptyList())
}
