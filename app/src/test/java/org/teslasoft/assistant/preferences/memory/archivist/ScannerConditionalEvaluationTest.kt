package org.teslasoft.assistant.preferences.memory.archivist

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.MemoryMatch
import org.teslasoft.assistant.preferences.memory.MemoryRecord
import org.teslasoft.assistant.preferences.memory.matching.*

/** The conditional model calls use their actual production instructions too. */
internal object ScannerConditionalEvaluationFixtures {
    val pairs = listOf(
        ComparisonPair(1,"pending","The user volunteers at Cedar Observatory on Saturday mornings since choir fills weekday evenings.",
            "pending","The user joined the Cedar Observatory volunteer team and chose Saturday mornings because weekday evenings conflict with choir.",
            "As I mentioned before, Saturday mornings at Cedar Observatory are my volunteer shift because choir takes weekday evenings; nothing has changed."),
        ComparisonPair(2,"archived","While restoring the 1978 telescope, the user discovered maker number T47 under its base plate.",
            "archived","The user restored a 1978 telescope and retained its original blue finish.",
            "A detail I never mentioned about that old restoration: I discovered maker number T47 under the telescope's base plate.")
    )
    val units = listOf(
        RankUnit(listOf(1,2), listOf("The observatory volunteer shift is Saturday mornings; weekday evenings conflict with choir.",
            "The observatory volunteer shift is Sunday mornings; weekday evenings conflict with choir.")),
        RankUnit(listOf(3),listOf("The user noticed cloud pattern number 18 today; no lasting event or decision resulted."))
    )
    fun comparison() = JSONObject().put("system",ScanComparisonRequest.SYSTEM).put("user",ScanComparisonRequest.render(pairs).data)
    fun ranking() = JSONObject().put("system",ScanRankingRequest.system(1)).put("user",ScanRankingRequest.render(units))
}

class ScannerConditionalEvaluationTest {
    private fun response(id:String)=javaClass.classLoader!!.getResourceAsStream("scanner_evaluation/$id.response.json")!!
        .bufferedReader().use{it.readText()}
    @Test fun actualComparisonResponseSuppressesOnlyTheRetellingAndKeepsTheArchivedDevelopment() = runBlocking {
        ScannerEvaluationRequests.assertCurrent("comparison",ScannerConditionalEvaluationFixtures.comparison())
        val cases=ScannerConditionalEvaluationFixtures.pairs
        val rendered=ScanComparisonRequest.render(cases)
        val covered=ScanComparisonRequest.coveredPairs(response("comparison"),rendered,cases.map{it.draftId to it.memoryId}.toSet())
        assertEquals(setOf(1 to "pending"),covered)
        val saved=cases.associate{it.memoryId to record(it.memoryId,it.memory,if(it.memoryStatus=="pending")"draft" else "archived")}
        var comparisons=0
        val plan=ScanFilingPlanner({saved[it]?.let{m->CurrentMemory(m,"hash")}},
            {id,_->cases.single{it.draftId==id}.wording},
            {sent->comparisons++;assertEquals(2,sent.size);ScanComparisonRequest.coveredPairs(response("comparison"),ScanComparisonRequest.render(sent),sent.map{it.draftId to it.memoryId}.toSet())},
            {_,_->error("The remaining development fits the cap")})
            .plan(cases.map{FilingDraft(it.draftId,it.proposal,MemoryMatch.placementKey("real_life",emptyList()),emptyList(),emptySet(),emptyList(),false,
                listOf(ScannerMatch(it.memoryId,null,if(it.memoryStatus=="pending")MatchStatus.PENDING else MatchStatus.ARCHIVED,MatchMethod.EMBEDDING,0.91f)))},1)
        assertEquals(1,comparisons);assertEquals(setOf(1),plan.covered)
        assertEquals(listOf(listOf(2)),plan.units);assertEquals(listOf("archived"),plan.memoryLinks[2])
    }
    @Test fun actualRankingResponseKeepsTheLinkedPairTogetherAtCapOne() {
        ScannerEvaluationRequests.assertCurrent("ranking",ScannerConditionalEvaluationFixtures.ranking())
        val units=ScannerConditionalEvaluationFixtures.units
        val kept=ScanRankingRequest.kept(response("ranking"),units.size,1)
        assertEquals(listOf(0),kept)
        assertEquals(listOf(1,2),units[kept.single()].draftIds)
    }
    private fun record(id:String,text:String,status:String)=MemoryRecord(id,"real_life",text,null,"[]",0,emptyList(),emptyList(),emptyList(),emptyList(),null,"[]","2026-01-01T00:00:00Z",null,status,null,emptyList(),emptyList(),emptyList())
}
