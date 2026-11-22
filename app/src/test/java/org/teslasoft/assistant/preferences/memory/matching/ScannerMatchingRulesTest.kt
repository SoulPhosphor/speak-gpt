/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.RetrievalScope
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistResponseParser.DraftMemory
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistSceneContext
import org.teslasoft.assistant.preferences.memory.archivist.WorkingDraft
import org.teslasoft.assistant.preferences.memory.archivist.WorkingDrafts

/** Stage 6B.2: draft eligibility, clue placement, saved evidence. */
class ScannerMatchingRulesTest {

    private fun draft(
        scope: String,
        targets: List<String> = emptyList(),
        scene: ArchivistSceneContext? = null,
        content: String = "A memory"
    ) = DraftMemory(
        content = content, scope = scope, typeIdSuggestion = null, tags = emptyList(), targetName = null,
        targetIds = targets, scene = scene
    )

    private val chatWithA = RetrievalScope("companion-a", null, null, null)
    private val chatWithB = RetrievalScope("companion-b", null, null, null)
    private val worldOne = RetrievalScope(null, "world-1", null, null)
    private val worldOneWithA = RetrievalScope("companion-a", "world-1", null, null, allowCompanionInRoleplay = true)

    @Test fun realLifeAndGlobalDraftsStayDiscoverableAcrossCompanions() {
        val realLife = draft("real_life", scene = ArchivistSceneContext("companion-a", null, null, null, null))
        assertTrue(ScannerDraftEligibility.eligible(realLife, chatWithB))
        assertTrue(ScannerDraftEligibility.eligible(draft("global"), chatWithB))
        assertTrue(ScannerDraftEligibility.eligible(draft("global"), worldOne))
        assertFalse("real life stays out of roleplay", ScannerDraftEligibility.eligible(realLife, worldOne))
    }

    @Test fun targetedDraftsStayWithTheirTargets() {
        val forA = draft("companion", listOf("companion-a"))
        assertTrue(ScannerDraftEligibility.eligible(forA, chatWithA))
        assertFalse(ScannerDraftEligibility.eligible(forA, chatWithB))
        assertFalse("no companion memory in roleplay unless allowed", ScannerDraftEligibility.eligible(forA, worldOne))
        assertTrue(ScannerDraftEligibility.eligible(forA, worldOneWithA))

        // A draft without resolved targets uses the scene it was written in.
        val sceneOnly = draft("companion", scene = ArchivistSceneContext("companion-a", null, null, null, null))
        assertTrue(ScannerDraftEligibility.eligible(sceneOnly, chatWithA))
        assertFalse(ScannerDraftEligibility.eligible(sceneOnly, chatWithB))

        val worldDraft = draft("world", listOf("world-1"))
        assertTrue(ScannerDraftEligibility.eligible(worldDraft, worldOne))
        assertFalse(ScannerDraftEligibility.eligible(worldDraft, RetrievalScope(null, "world-2", null, null)))
        assertFalse(ScannerDraftEligibility.eligible(worldDraft, chatWithA))

        assertTrue(ScannerDraftEligibility.eligible(draft("project", listOf("p")), chatWithB))
        assertFalse(ScannerDraftEligibility.eligible(draft("project", listOf("p")), worldOne))
        assertFalse(ScannerDraftEligibility.eligible(draft("unknown"), chatWithA))
    }

    @Test fun memoryStatusesMapToComparisonStatuses() {
        assertEquals(MatchStatus.ACTIVE, MatchStatus.ofMemoryStatus("active"))
        assertEquals(MatchStatus.PENDING, MatchStatus.ofMemoryStatus("draft"))
        assertEquals(MatchStatus.ARCHIVED, MatchStatus.ofMemoryStatus("archived"))
        assertNull(MatchStatus.ofMemoryStatus("superseded"))
    }

    @Test fun cluesJoinThePromptListWithoutPushingTheTopResultsOut() {
        val searched = (1..12).map { "s$it" }
        val merged = ScannerClues.forPrompt(searched, listOf("s2", "c1", "c2", "c1"), 10) { it }
        assertEquals(14, merged.size)
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "c1", "c2"), merged.take(10))
        assertEquals(listOf("s9", "s10", "s11", "s12"), merged.drop(10))

        assertEquals(listOf("a", "c"), ScannerClues.forPrompt(listOf("a"), listOf("c"), 10) { it })
        assertEquals(listOf("a"), ScannerClues.forPrompt(listOf("a"), emptyList(), 10) { it })
        val five = ScannerClues.forPrompt(emptyList(), (1..5).map { "c$it" }, 10) { it }
        assertEquals(ScannerClues.MAX_CLUES, five.size)
    }

    private val evidence = DraftMatchEvidence(
        proposal = MatchEvidence(
            fingerprint = "fp-1",
            modelKey = "gemma-256|doc-2",
            matches = listOf(
                ScannerMatch("mem-1", null, MatchStatus.ARCHIVED, MatchMethod.EMBEDDING, 0.91f),
                ScannerMatch(null, 4, MatchStatus.WORKING_DRAFT, MatchMethod.KEYWORD)
            ),
            links = listOf(ScannerMatch("mem-2", null, MatchStatus.ACTIVE, MatchMethod.AI_RELATED)),
            unexamined = 3
        ),
        sections = listOf(ScannerMatch("mem-3", null, MatchStatus.PENDING, MatchMethod.CHAT_CLUE))
    )

    @Test fun evidenceIsSavedWithTheCheckpointAndRestored() {
        val drafts = WorkingDrafts(
            listOf(WorkingDraft(7, draft("real_life", content = "The user plays violin"), evidence = evidence)), 8
        )
        val restored = WorkingDrafts.restore(drafts.drafts.map(WorkingDrafts::payload), 8)
        assertEquals(evidence, restored.drafts.single().evidence)
        assertFalse(restored.drafts.single().evidence!!.proposal!!.semanticComplete)
    }

    @Test fun savedEvidenceHoldsNoText() {
        val json = MatchEvidenceCodec.encode(evidence).toString()
        assertFalse(json.contains("violin"))
        assertTrue(json.contains("mem-1"))
    }

    @Test fun aKeywordScoreIsNeverRestored() {
        val tampered = MatchEvidenceCodec.encode(evidence).apply {
            getJSONObject("proposal").getJSONArray("matches").getJSONObject(1).put("score", 0.5)
        }
        val keyword = MatchEvidenceCodec.decode(tampered)!!.proposal!!.matches[1]
        assertEquals(MatchMethod.KEYWORD, keyword.method)
        assertNull(keyword.score)
    }

    @Test fun evidenceIsDeferredUntilItMatchesTheCurrentWordingAndModel() {
        assertTrue(DraftMatchEvidence().needsLookup("fp-1", null))
        assertFalse(evidence.needsLookup("fp-1", "gemma-256|doc-2"))
        assertTrue("wording changed", evidence.needsLookup("fp-2", "gemma-256|doc-2"))
        assertTrue("model changed", evidence.needsLookup("fp-1", "other|doc-2"))
        assertTrue("model became available", evidence.copy(
            proposal = evidence.proposal!!.copy(modelKey = null)
        ).needsLookup("fp-1", "gemma-256|doc-2"))
    }

    @Test fun anEditedDraftKeepsItsOldEvidenceOnlyAsOutOfDate() {
        val d = WorkingDraft(1, draft("real_life", content = "First wording"), evidence = evidence)
        val before = ScannerMatching.draftRecord(d)
        val edited = d.copy(memory = d.memory.copy(content = "Corrected wording"))
        assertTrue(before.fingerprint != ScannerMatching.draftRecord(edited).fingerprint)
    }

    @Test fun sectionEvidenceKeepsTheNewestFirstWithoutTheDraftItself() {
        val older = listOf(
            ScannerMatch("m1", null, MatchStatus.ACTIVE, MatchMethod.KEYWORD),
            ScannerMatch("m2", null, MatchStatus.ARCHIVED, MatchMethod.KEYWORD)
        )
        val latest = listOf(
            ScannerMatch(null, 5, MatchStatus.WORKING_DRAFT, MatchMethod.EMBEDDING, 0.6f),
            ScannerMatch("m2", null, MatchStatus.ARCHIVED, MatchMethod.EMBEDDING, 0.7f)
        )
        val merged = ScannerMatching.mergeSection(older, latest, selfId = 5)
        assertEquals(listOf("m:m2", "m:m1"), merged.map { it.key })
        assertEquals(MatchMethod.EMBEDDING, merged.first().method)
        val many = (1..30).map { ScannerMatch("x$it", null, MatchStatus.ACTIVE, MatchMethod.KEYWORD) }
        assertEquals(DraftMatchEvidence.MAX_SECTION_MATCHES, ScannerMatching.mergeSection(many, emptyList(), 1).size)
    }
}
