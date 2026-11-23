package org.teslasoft.assistant.preferences.memory

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.R
import org.teslasoft.assistant.ui.activities.PersonasListActivity
import org.teslasoft.assistant.ui.activities.memory.PendingPairReviewController

@RunWith(AndroidJUnit4::class)
class PendingPairReviewInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val opened = ArrayList<Pair<MemoryStore,String>>()
    @Before fun nativeLibrary() { System.loadLibrary("sqlcipher") }
    @After fun cleanup() { opened.forEach { (store,name) -> store.close(); context.deleteDatabase(name) } }
    private fun record(id:String)=MemoryRecord(id,"global","Wording $id","old derived $id","[]",0,
        emptyList(),emptyList(),emptyList(),emptyList(),null,"[]","2026-01-01T00:00:00Z",null,"draft",null,emptyList(),emptyList(),emptyList())
    private fun pair(linked:Boolean=true):Pair<MemoryStore,PendingPairSnapshot> {
        val name="pending_pair_${System.nanoTime()}.db"
        val store=MemoryStore.openForTest(context,name,"pending-pair-test-key".toByteArray())
        opened.add(store to name)
        val run=MemoryStore.newId("run-")
        val range=ScanRangeRecord(MemoryStore.newId("range-"),run,"synthetic-chat",0,ScanRangeRecord.KIND_RERUN,emptyList(),emptyList())
        store.beginScanRun(ArchivistRunRecord(runId=run,startedAt=MemoryStore.nowIso(),finishedAt=null,status="running",
            chatIdsJson="[]",transcriptIdsJson="[]",memoryIdsJson="[]",ruleIdsJson="[]",foundCount=0,failedChatIdsJson="[]",error=null),listOf(range))
        store.commitScanRange(range,emptyList(),listOf(record("a"),record("b")),emptyList(),emptyList(),
            relationshipHintsByMemoryId=if(linked)mapOf("a" to listOf("b"),"b" to listOf("a"))else emptyMap())
        return store to store.capturePendingPair("a","b")!!
    }
    @Test fun keepBothPreservesPendingAndBothLinks() {
        val(store,shown)=pair()
        assertTrue(store.resolvePendingPair(shown,PendingPairAction.KEEP_BOTH) is MemoryStore.ResolutionResult.Applied)
        assertEquals(shown.proposal,store.getMemory("a")); assertEquals(shown.related,store.getMemory("b"))
        assertEquals(shown.links,store.capturePendingPair("a","b")!!.links)
    }
    @Test fun editEitherSideKeepsIdentityPlacementAndPendingWhileInvalidatingDerivedText() {
        for(id in listOf("a","b")) {
            val(store,shown)=pair()
            assertTrue(store.resolvePendingPair(shown,PendingPairAction.EDIT,id,"Corrected $id") is MemoryStore.ResolutionResult.Applied)
            val edited=store.getMemory(id)!!
            assertEquals("Corrected $id",edited.content);assertEquals("draft",edited.status);assertEquals("global",edited.scope)
            assertEquals("2026-01-01T00:00:00Z",edited.createdAt);assertNull(edited.embeddingText)
            val other=if(id=="a")"b" else "a"
            assertEquals(shown.records.single{it.memoryId==other},store.getMemory(other))
            assertEquals(shown.links,store.capturePendingPair("a","b")!!.links)
        }
    }
    @Test fun discardOnlyTheChosenGeneratedDraftAndRetainItsExactRejection() {
        for(id in listOf("a","b")) {
            val(store,shown)=pair()
            assertTrue(store.resolvePendingPair(shown,PendingPairAction.DISCARD,id) is MemoryStore.ResolutionResult.Applied)
            assertNull(store.getMemory(id));assertTrue(store.isDraftRejected("Wording $id"))
            val other=if(id=="a")"b" else "a"
            assertEquals(shown.records.single{it.memoryId==other},store.getMemory(other))
            assertTrue(store.relationshipHintsForDraft(other).isEmpty())
        }
    }
    @Test fun explicitSaveActivatesOnlyTheChosenSideAndRetainsTheOtherForReview() {
        for(id in listOf("a","b")) {
            val(store,shown)=pair()
            val applied=store.resolvePendingPair(shown,PendingPairAction.SAVE,id) as MemoryStore.ResolutionResult.Applied
            assertEquals(listOf(id),applied.reindexMemoryIds);assertEquals("active",store.getMemory(id)!!.status)
            val other=if(id=="a")"b" else "a"
            assertEquals("draft",store.getMemory(other)!!.status)
            assertEquals(listOf(id),store.relationshipHintsForDraft(other).map{it.memoryId})
        }
    }
    @Test fun everyActionRejectsEitherRecordOrRelationshipChangingDuringTheWait() {
        for(action in PendingPairAction.entries)for(id in listOf("a","b"))for(change in listOf("edit","approve","discard","placement","link")) {
            val(store,shown)=pair();val prior=store.getMemory(id)!!
            when(change) {
                "edit"->store.updateMemory(prior.copy(content="current wording"),null)
                "approve"->store.setMemoryStatus(id,"active",null)
                "discard"->store.deleteMemory(id)
                "placement"->store.updateMemory(prior.copy(scope="real_life"),null)
                "link"->store.writableDatabase.delete("memory_possible_match_hints","draft_memory_id = ?",arrayOf(id))
            }
            val before=listOf(store.getMemory("a"),store.getMemory("b"))
            assertEquals("$action/$id/$change",MemoryStore.ResolutionResult.StaleSelection,
                store.resolvePendingPair(shown,action,"a","must not be written"))
            assertEquals(before,listOf(store.getMemory("a"),store.getMemory("b")))
        }
    }
    @Test fun ordinaryPendingMatchesHaveTheSameProtectionAndLegacyActionsRejectAllPendingTargets() {
        val(store,shown)=pair(false)
        val expected=shown.records.associate{it.memoryId to PendingMatchPresentation.reviewFingerprint(it)}
        assertEquals(MemoryStore.ResolutionResult.StaleSelection,store.resolveReplace("a",listOf("b"),expected))
        assertEquals(MemoryStore.ResolutionResult.StaleSelection,store.resolveSupersede("a",listOf("b"),expected))
        assertEquals(MemoryStore.ResolutionResult.StaleSelection,store.resolveSaveAndEditOld("a",shown.related.copy(content="wrong"),expected))
        assertEquals(MemoryStore.ResolutionResult.StaleSelection,store.resolvePendingPair(shown,PendingPairAction.SAVE,"unrelated"))
        assertNull(store.capturePendingPair("a","a"))
        assertTrue(store.resolvePendingPair(shown,PendingPairAction.EDIT,"b","Edited ordinary suggestion") is MemoryStore.ResolutionResult.Applied)
        assertEquals("draft",store.getMemory("a")!!.status);assertEquals("draft",store.getMemory("b")!!.status)
    }
    @Test fun actualControlsEditAndCancelWithoutDispatchingAWritingOperation() {
        val(_,shown)=pair()
        ActivityScenario.launch<PersonasListActivity>(Intent(context,PersonasListActivity::class.java)).use{scenario->
            scenario.onActivity{activity->
                var dispatched=0;var locked=false
                val controller=PendingPairReviewController(activity,shown,{dispatched++},{locked=it},{},{})
                val host=LinearLayout(activity)
                val cards=shown.records.map{record->
                    (activity.layoutInflater.inflate(R.layout.view_review_memory_card,host,false) as LinearLayout).also{
                        host.addView(it);controller.bindCard(it,record)
                    }
                }
                controller.addKeepBoth(host)
                val first=cards.first()
                first.findViewById<View>(R.id.pair_edit).performClick()
                assertTrue(locked);assertEquals(View.VISIBLE,first.findViewById<View>(R.id.pair_editor).visibility)
                assertEquals(shown.proposal.content,first.findViewById<EditText>(R.id.pair_content).text.toString())
                assertFalse(cards[1].findViewById<View>(R.id.pair_save).isEnabled)
                assertFalse(host.findViewById<View>(R.id.pair_keep).isEnabled)
                first.findViewById<EditText>(R.id.pair_content).setText("Unsaved correction")
                first.findViewById<View>(R.id.pair_edit_cancel).performClick()
                assertFalse(locked);assertEquals(0,dispatched)
                assertEquals(View.GONE,first.findViewById<View>(R.id.pair_editor).visibility)
                assertTrue(cards[1].findViewById<View>(R.id.pair_save).isEnabled)
                activity.layoutInflater.inflate(R.layout.dialog_pending_pair_discard,null)
                controller.addOpenPair(cards[1]){dispatched++}
            }
        }
    }
}
