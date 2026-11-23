package org.teslasoft.assistant.preferences.memory.archivist

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.includes.IncludeTextPolicy
import org.teslasoft.assistant.preferences.memory.*
import org.teslasoft.assistant.preferences.memory.matching.*

/** Recorded independent extraction responses, replayed through production
 * parsing, working-draft correction, matching, reconciliation and presentation.
 * Device/store transactions are exercised in PendingPairReviewInstrumentedTest.
 * Reported token values are estimates, never invented provider usage or cost. */
class ScannerEndToEndEvaluationTest {
    private fun fixture(id: String) = ScannerEvaluationFixtures.cases.single { it.id == id }
    private fun response(id: String) = javaClass.classLoader!!.getResourceAsStream("scanner_evaluation/$id.response.json")!!
        .bufferedReader().use { it.readText() }
    private fun replay(id: String): ArchivistResponseParser.Parsed = runBlocking {
        val c = fixture(id)
        var calls = 0
        val result = ArchivistRequestExecutor.associativePrepared(c.request().getString("system"), c.protocol,
            c.request().getString("user"), "fixture-model", 0.3, ArchivistModelTransport { request ->
                calls++
                assertEquals(c.request().getString("user"), request.conversationData)
                assertEquals(1, request.attempt)
                response(id)
            })
        assertEquals("$id requires no output repair", 1, calls)
        assertEquals("$id loses no rows to parsing", 0, result.dropped)
        result
    }
    @Test fun allIndependentResponsesPassTheProductionParserWithoutRepair() {
        assertEquals(13, ScannerEvaluationFixtures.cases.size)
        val export = System.getenv("SCANNER_EVALUATION_EXPORT")?.let(::File)?.apply { mkdirs() }
        for (c in ScannerEvaluationFixtures.cases) {
            val parsed = replay(c.id)
            assertTrue(parsed.rules.isEmpty())
            export?.resolve("${c.id}.json")?.writeText(c.request().toString(2))
            val request = c.request()
            val input = IncludeTextPolicy.estimateTokens(request.getString("system")) + IncludeTextPolicy.estimateTokens(request.getString("user"))
            println("scanner_evaluation case=${c.id} extraction_requests=1 estimated_input_tokens=$input " +
                "estimated_output_tokens=${IncludeTextPolicy.estimateTokens(response(c.id))} provider_usage=Not_Reported provider_cost=Not_Reported")
        }
    }
    @Test fun actualProsePreservesChronologyTopicCoverageAndEmailAttribution() {
        assertEquals(1, replay("short").memories.size)
        for (id in listOf("late-correction", "middle-correction")) {
            assertEquals(80, fixture(id).rows.size)
            val memories = replay(id).memories
            assertEquals(3, memories.size)
            val event = memories.single { "Sunday" in it.content }
            assertTrue("Saturday" in event.content && "choir" in event.content)
            val all = memories.joinToString(" ") { it.content }
            for (word in listOf("1978", "blue", "May", "Ivo")) assertTrue("$id / $word", word in all)
        }
        val email = replay("email-correction").memories.joinToString(" ") { it.content }
        assertTrue(Regex("Ivo.{0,150}Friday", RegexOption.DOT_MATCHES_ALL).containsMatchIn(email))
        assertTrue("Tuesday" in email)
    }
    @Test fun actualWorkingDraftCorrectionsKeepOneIdentityAcrossCheckpointRecovery() {
        for (id in listOf("working-correction", "long-tail")) {
            val c = fixture(id)
            val before = WorkingDrafts(listOf(WorkingDraft(1,
                ArchivistResponseParser.DraftMemory(c.working.single().content, "real_life", "fact", emptyList(), null))), 2)
            val parsed = replay(id)
            assertTrue(parsed.draftEdits.isNotEmpty())
            val checked = WorkingDraftEdits.validate(parsed.draftEdits, parsed.withdrawnDrafts,
                c.protocol.draftIdByRef, setOf(1), WorkingDraftEdits.samePlacement(before))
            assertTrue(checked.failed.isEmpty())
            val after = before.applying(SectionDraftChanges(operations = checked.valid, newDrafts = parsed.memories))
            assertEquals(1, after.drafts.size)
            assertEquals(1, after.drafts.single().id)
            assertTrue("Sunday" in after.drafts.single().memory.content && "choir" in after.drafts.single().memory.content)
            assertEquals(after, WorkingDrafts.restore(after.drafts.map(WorkingDrafts::payload), after.nextId))
        }
    }
    @Test fun actualRetellingsAreCoveredAndGenuineUpdatesAndSpecificInstructionsRemainReviewable() {
        for (id in listOf("retelling", "archived-retelling", "different-listener")) {
            val parsed = replay(id)
            assertTrue(parsed.memories.isEmpty())
            val c = fixture(id)
            val old = memory("observatory", c.existing.single().content)
            val covered = CoveredEntries.validate(parsed.alreadyCovered, c.protocol.memoryIdByAlias, c.rows) {
                MemoryDependencies.of(old, "wording-hash")
            }
            assertEquals("$id must retain suppression evidence", 1, covered.size)
            assertEquals("observatory", covered.single().dependency.memoryId)
            assertEquals(c.rows.first().messageId, covered.single().passages.single().messageId)
        }
        for (id in listOf("new-decision", "archived-detail", "archived-return")) {
            val p = replay(id)
            assertTrue(p.memories.any { "observatory" in it.relatedExistingMemoryIds })
            assertTrue(p.alreadyCovered.isEmpty())
        }
        val specific = replay("companion-instruction").memories.single()
        assertEquals("companion", specific.scope)
        assertEquals(listOf("comp-b"), specific.targetIds)
        assertTrue("C7" in specific.content)
    }
    @Test fun realResponseFlowsThroughMatchingAndReviewWithAndWithoutVectors() = runBlocking {
        val update = replay("new-decision").memories.single()
        val old = memory("observatory", fixture("new-decision").existing.single().content)
        val proposal = ComparisonRecord(null, 1, MatchStatus.WORKING_DRAFT, update.content, update.content, "new-fp")
        val saved = ComparisonRecord(old.memoryId, null, MatchStatus.PENDING, old.content, old.content, "old-fp")
        for (withVectors in listOf(false, true)) {
            val vectors = if (withVectors) CountingVectors() else null
            val evidence = ScannerMatchEngine.lookupProposal(proposal, listOf(saved), update.relatedExistingMemoryIds, emptyList(), vectors)
            if (!withVectors) assertTrue(evidence.matches.all { it.score == null })
            var comparisons = 0; var rankings = 0
            val plan = ScanFilingPlanner({ if (it == old.memoryId) CurrentMemory(old, "wording-hash") else null },
                { _, _ -> fixture("new-decision").texts.first() }, { comparisons++; emptySet() }, { _, _ -> rankings++; emptyList() })
                .plan(listOf(FilingDraft(1, update.content, MemoryMatch.placementKey("real_life", emptyList()),
                    update.relatedExistingMemoryIds, setOf("observatory"), emptyList(), false, evidence.matches)), 1)
            assertEquals(listOf(listOf(1)), plan.units)
            assertEquals(listOf("observatory"), plan.memoryLinks[1])
            assertEquals(0, comparisons); assertEquals(0, rankings)
            val presented = PendingMatchPresentation.order(listOf(MemoryMatch.Match("observatory", MemoryMatch.Relation.AI_RELATED)), { "draft" }, { false })
            assertTrue(presented.single().reviewOnly)
            if (vectors != null) {
                val cold = vectors.generations
                ScannerMatchEngine.lookupProposal(proposal, listOf(saved), update.relatedExistingMemoryIds, emptyList(), vectors)
                assertEquals(cold, vectors.generations)
                ScannerMatchEngine.lookupProposal(proposal.copy(fingerprint="changed"), listOf(saved), update.relatedExistingMemoryIds, emptyList(), vectors)
                assertEquals(cold + 1, vectors.generations)
            }
        }
    }
    @Test fun exhaustedConflictingEditsYieldOneCapSlotWithBothPendingWordings() = runBlocking {
        val initial = ArchivistResponseParser.DraftMemory("The observatory shift is Saturday.", "real_life", null, emptyList(), null)
        val before = WorkingDrafts(listOf(WorkingDraft(1, initial)), 2)
        val unresolved = linkedSetOf<Int>()
        val checked = WorkingDraftEdits.resolve(
            listOf(ArchivistResponseParser.RawDraftEdit(listOf("D1"), "The observatory shift is Sunday.", null, null)),
            listOf("D1", "invented"), mapOf("D1" to 1), setOf(1), WorkingDraftEdits.samePlacement(before),
            repair = { null }, unresolvedIds = unresolved)
        val after = before.applying(SectionDraftChanges(operations=checked.first, alternatives=checked.second.first, unresolvedIds=unresolved))
        assertEquals(2, after.drafts.size)
        val plan = ScanFilingPlanner({null}, {_,_->"support"}, {error("No comparison needed")}, {_,_->error("The pair is one cap slot")})
            .plan(after.drafts.map { FilingDraft(it.id,it.memory.content,WorkingDrafts.placement(it.memory),emptyList(),emptySet(),it.alternativeOf,it.unresolvedEdit,emptyList()) },1)
        assertEquals(setOf(1,2),plan.units.single().toSet())
        val displayed=PendingMatchPresentation.order(listOf(MemoryMatch.Match("other",MemoryMatch.Relation.AI_RELATED)),{"draft"},{true})
        assertEquals(PendingMatchPresentation.Method.ALTERNATIVE,displayed.single().method)
        assertTrue(displayed.single().reviewOnly)
    }
    @Test fun lateDiscoveredMatchesUseOneConditionalComparisonAndRankingOnlyWhenOverTheCap() = runBlocking {
        val old = memory("old", "The user volunteers on Saturdays.")
        var comparisons = 0
        var rankings = 0
        val memo = ScanFilingPlanner.Memo()
        val draft = FilingDraft(1, "The user now volunteers on Sundays.", MemoryMatch.placementKey("real_life", emptyList()),
            emptyList(), emptySet(), emptyList(), false,
            listOf(ScannerMatch("old", null, MatchStatus.PENDING, MatchMethod.EMBEDDING, 0.91f)))
        val planner = ScanFilingPlanner({ CurrentMemory(old, "hash") }, { _, _ -> "Sunday is confirmed." },
            { comparisons++; emptySet() }, { _, _ -> rankings++; listOf(1) }, memo)
        val first = planner.plan(listOf(draft), 1)
        assertEquals(1, comparisons)
        assertEquals(0, rankings)
        assertEquals(listOf("old"), first.memoryLinks[1])
        planner.plan(listOf(draft), 1)
        assertEquals("A recheck never sends a second comparison", 1, comparisons)
        planner.plan(listOf(draft, draft.copy(draftId=2,content="The telescope keeps its blue finish.",matches=emptyList())), 1)
        assertEquals(1, rankings)
    }
    @Test fun exactRetellingsUnderDifferentTypeAndNoTypeMakeNoExtraCards() = runBlocking {
        val old = memory("old", "The user volunteers on Saturdays.")
        for(type in listOf("different-type", null)) {
            val saved = old.copy(typeId=type)
            val plan = ScanFilingPlanner({CurrentMemory(saved,"hash")},{_,_->"same event"},
                {error("An exact retelling needs no comparison")},{_,_->error("No distinct proposals remain")})
                .plan(listOf(FilingDraft(1,old.content,MemoryMatch.placementKey("real_life",emptyList()),
                    emptyList(),emptySet(),emptyList(),false,
                    listOf(ScannerMatch("old",null,MatchStatus.PENDING,MatchMethod.EXACT)))),1)
            assertTrue(plan.units.isEmpty())
            assertEquals(setOf(1),plan.covered)
            assertEquals(type,saved.typeId)
        }
    }
    private class CountingVectors: ComparisonVectors {
        override val modelKey="synthetic-model"
        private val cached=HashMap<String,FloatArray>()
        var generations=0
        override fun known(records:List<ComparisonRecord>)=records.mapNotNull { r->cached[r.key+r.fingerprint]?.let{r.key to it} }.toMap()
        override fun generate(record:ComparisonRecord):FloatArray { generations++; return floatArrayOf(1f,0f).also{cached[record.key+record.fingerprint]=it} }
        override fun query(text:String)=floatArrayOf(1f,0f)
    }
    private fun memory(id:String,text:String)=MemoryRecord(id,"real_life",text,null,"[]",0,emptyList(),emptyList(),emptyList(),emptyList(),null,"[]","2026-01-01T00:00:00Z",null,"draft",null,emptyList(),emptyList(),emptyList())
}
