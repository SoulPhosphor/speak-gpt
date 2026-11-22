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

/** Stage 6B.2: the scanner's scoped matching engine. */
class ScannerMatchEngineTest {

    /** Vectors keyed by topic word: a text's vector is the sum of the
     *  one-hot directions of the topics it mentions. */
    private class FakeVectors(
        val topics: List<String> = listOf("violin", "garden", "promotion", "move"),
        /** Vectors already available, by record key + fingerprint. */
        val stored: MutableMap<String, FloatArray> = HashMap(),
        val failGenerateFor: Set<String> = emptySet()
    ) : ComparisonVectors {
        override val modelKey = "model-a|doc-2"
        val generated = ArrayList<String>()
        val queries = ArrayList<String>()

        fun vec(text: String): FloatArray {
            val lower = text.lowercase()
            return FloatArray(topics.size) { i -> if (lower.contains(topics[i])) 1f else 0f }
        }

        fun store(r: ComparisonRecord) { stored[r.key + "|" + r.fingerprint] = vec(r.document) }

        override fun known(records: List<ComparisonRecord>): Map<String, FloatArray> =
            records.mapNotNull { r -> stored[r.key + "|" + r.fingerprint]?.let { r.key to it } }.toMap()

        override fun canGenerate(record: ComparisonRecord) = record.status != MatchStatus.ACTIVE

        override fun generate(record: ComparisonRecord): FloatArray? {
            generated.add(record.key)
            if (record.key in failGenerateFor) return null
            return vec(record.document).also { stored[record.key + "|" + record.fingerprint] = it }
        }

        override fun query(text: String): FloatArray? {
            queries.add(text)
            return vec(text)
        }
    }

    private fun memory(id: String, content: String, status: MatchStatus, fp: String = "fp-$id", updated: String? = null) =
        ComparisonRecord(id, null, status, content, content, fp, updated)

    private fun draft(id: Int, content: String, fp: String = "fp-d$id") =
        ComparisonRecord(null, id, MatchStatus.WORKING_DRAFT, content, content, fp)

    private val active = memory("a1", "The user plays the violin every evening", MatchStatus.ACTIVE)
    private val pending = memory("p1", "The user started violin lessons with a new teacher", MatchStatus.PENDING)
    private val archived = memory("r1", "The user used to tend a vegetable garden", MatchStatus.ARCHIVED)
    private val unrelated = memory("u1", "The user's sister moved abroad", MatchStatus.ACTIVE)

    @Test fun withoutAModelMatchesAreKeywordOrExactAndNeverScored() {
        val evidence = ScannerMatchEngine.lookupProposal(
            self = draft(1, "The user takes violin lessons with a teacher"),
            records = listOf(active, pending, archived),
            aiRelatedIds = emptyList(), alternativeOfIds = emptyList(), vectors = null
        )
        assertNull(evidence.modelKey)
        assertFalse(evidence.semanticComplete)
        assertEquals(3, evidence.unexamined)
        assertEquals(listOf("m:p1"), evidence.matches.map { it.key })
        assertEquals(MatchMethod.KEYWORD, evidence.matches.single().method)
        assertNull(evidence.matches.single().score)
        assertEquals(MatchStatus.PENDING, evidence.matches.single().status)
    }

    @Test fun exactWordingIsFoundWithoutAModel() {
        val evidence = ScannerMatchEngine.lookupProposal(
            self = draft(1, "the user plays the VIOLIN every evening"),
            records = listOf(active), aiRelatedIds = emptyList(), alternativeOfIds = emptyList(), vectors = null
        )
        assertEquals(MatchMethod.EXACT, evidence.matches.single().method)
        assertNull(evidence.matches.single().score)
    }

    @Test fun everyStatusIsFoundAndLabeledWithItsRealScore() {
        val vectors = FakeVectors()
        listOf(active, pending, archived, unrelated).forEach(vectors::store)
        val d2 = draft(2, "Violin practice moved to mornings").also(vectors::store)
        val evidence = ScannerMatchEngine.lookupProposal(
            self = draft(1, "The user practises violin"),
            records = listOf(active, pending, archived, unrelated, d2),
            aiRelatedIds = emptyList(), alternativeOfIds = emptyList(), vectors = vectors
        )
        assertEquals("model-a|doc-2", evidence.modelKey)
        assertEquals(0, evidence.unexamined)
        assertTrue(evidence.semanticComplete)
        val byKey = evidence.matches.associateBy { it.key }
        assertEquals(MatchStatus.ACTIVE, byKey.getValue("m:a1").status)
        assertEquals(MatchStatus.PENDING, byKey.getValue("m:p1").status)
        assertFalse("garden is not violin", "m:r1" in byKey)
        assertFalse("unrelated record", "m:u1" in byKey)
        // "violin" and "move" both appear in the other draft: cosine 0.707, below the bar.
        assertFalse("m:d2" in byKey || "d:2" in byKey)
        byKey.values.forEach {
            assertEquals(MatchMethod.EMBEDDING, it.method)
            assertEquals(1f, it.score!!, 1e-6f)
        }
    }

    @Test fun archivedAndDraftRecordsMatchByMeaning() {
        val vectors = FakeVectors()
        val d2 = draft(2, "Planning a garden for the spring")
        val evidence = ScannerMatchEngine.lookupProposal(
            self = draft(1, "The user wants to grow a garden again"),
            records = listOf(archived, d2),
            aiRelatedIds = emptyList(), alternativeOfIds = emptyList(), vectors = vectors
        )
        assertEquals(setOf("m:r1", "d:2"), evidence.matches.map { it.key }.toSet())
        assertEquals(
            setOf(MatchStatus.ARCHIVED, MatchStatus.WORKING_DRAFT),
            evidence.matches.map { it.status }.toSet()
        )
    }

    @Test fun theProposalNeverMatchesItself() {
        val self = draft(1, "The user plays violin")
        val evidence = ScannerMatchEngine.lookupProposal(
            self, listOf(self), emptyList(), emptyList(), FakeVectors()
        )
        assertTrue(evidence.matches.isEmpty())
    }

    @Test fun missingVectorsAreGeneratedWithinTheBoundAndTheRestFallBackToKeywords() {
        val vectors = FakeVectors()
        val pendings = (1..6).map { memory("p$it", "Violin recital number $it planned", MatchStatus.PENDING) }
        val evidence = ScannerMatchEngine.lookupProposal(
            self = draft(1, "The violin recital is planned").also(vectors::store),
            records = pendings,
            aiRelatedIds = emptyList(), alternativeOfIds = emptyList(), vectors = vectors,
            limits = ScannerMatchEngine.Limits(maxMissingVectors = 2)
        )
        assertEquals(2, vectors.generated.size)
        assertEquals(4, evidence.unexamined)
        assertFalse(evidence.semanticComplete)
        assertEquals(2, evidence.matches.count { it.method == MatchMethod.EMBEDDING })
        assertTrue(evidence.matches.filter { it.method == MatchMethod.KEYWORD }.all { it.score == null })
        assertTrue(evidence.matches.any { it.method == MatchMethod.KEYWORD })
    }

    @Test fun activeMemoriesWithoutAnIndexVectorAreNotReadButCountedAndKeywordMatched() {
        val vectors = FakeVectors()
        val evidence = ScannerMatchEngine.lookupProposal(
            self = draft(1, "The user plays violin every evening").also(vectors::store),
            records = listOf(active), aiRelatedIds = emptyList(), alternativeOfIds = emptyList(), vectors = vectors
        )
        assertTrue(vectors.generated.isEmpty())
        assertEquals(1, evidence.unexamined)
        assertEquals(MatchMethod.KEYWORD, evidence.matches.single().method)
    }

    @Test fun aChangedRecordNeverReusesItsOldVector() {
        val vectors = FakeVectors()
        val before = memory("p1", "The user plays violin", MatchStatus.PENDING, fp = "v1")
        vectors.store(before)
        val edited = memory("p1", "The user plays violin", MatchStatus.PENDING, fp = "v2")
        ScannerMatchEngine.lookupProposal(
            draft(1, "violin").also(vectors::store), listOf(edited), emptyList(), emptyList(), vectors
        )
        assertEquals(listOf("m:p1"), vectors.generated)
        vectors.generated.clear()
        ScannerMatchEngine.lookupProposal(
            draft(1, "violin"), listOf(edited), emptyList(), emptyList(), vectors
        )
        assertTrue("the new vector is reused", vectors.generated.isEmpty())
    }

    @Test fun aFailedReadIsCountedNotScored() {
        val vectors = FakeVectors(failGenerateFor = setOf("m:p1"))
        val evidence = ScannerMatchEngine.lookupProposal(
            draft(1, "violin lessons teacher").also(vectors::store), listOf(pending), emptyList(), emptyList(), vectors
        )
        assertEquals(1, evidence.unexamined)
        assertEquals(MatchMethod.KEYWORD, evidence.matches.single().method)
    }

    @Test fun whenTheProposalItselfCannotBeReadNothingClaimsMeaningEvidence() {
        val vectors = FakeVectors(failGenerateFor = setOf("d:1"))
        vectors.store(pending)
        val evidence = ScannerMatchEngine.lookupProposal(
            draft(1, "violin lessons teacher"), listOf(pending), emptyList(), emptyList(), vectors
        )
        assertNull(evidence.modelKey)
        assertEquals(1, evidence.unexamined)
        assertTrue(evidence.matches.none { it.method == MatchMethod.EMBEDDING })
    }

    @Test fun comparisonsAreBoundedAndPreferRecordsSharingWords() {
        val vectors = FakeVectors()
        val many = (1..50).map { memory("x$it", "Notes about item $it", MatchStatus.PENDING) }
        val relevant = memory("p9", "Violin recital rehearsal", MatchStatus.PENDING)
        val evidence = ScannerMatchEngine.lookupProposal(
            draft(1, "violin recital").also(vectors::store), many + relevant, emptyList(), emptyList(), vectors,
            ScannerMatchEngine.Limits(maxCompared = 5, maxMissingVectors = 5)
        )
        assertEquals(5, vectors.generated.size)
        assertEquals("m:p9", vectors.generated.first())
        assertEquals(46, evidence.unexamined)
        assertEquals("m:p9", evidence.matches.first().key)
    }

    @Test fun resultsAreBounded() {
        val vectors = FakeVectors()
        val records = (1..20).map { memory("p$it", "violin practice $it", MatchStatus.PENDING) }
        val evidence = ScannerMatchEngine.lookupProposal(
            draft(1, "violin").also(vectors::store), records, emptyList(), emptyList(), vectors,
            ScannerMatchEngine.Limits(maxMatches = 3, maxMissingVectors = 20)
        )
        assertEquals(3, evidence.matches.size)
    }

    @Test fun linksAreKeptOnlyForEligibleRecords() {
        val d3 = draft(3, "Another wording of the lessons")
        val evidence = ScannerMatchEngine.lookupProposal(
            self = draft(1, "Lessons"),
            records = listOf(pending, d3),
            aiRelatedIds = listOf("p1", "deleted-or-ineligible"),
            alternativeOfIds = listOf(3, 99),
            vectors = null
        )
        assertEquals(
            listOf(
                ScannerMatch("p1", null, MatchStatus.PENDING, MatchMethod.AI_RELATED),
                ScannerMatch(null, 3, MatchStatus.WORKING_DRAFT, MatchMethod.ALTERNATIVE)
            ),
            evidence.links
        )
    }

    @Test fun aMemoryChangedBetweenLookupsIsReportedAsItIsNow() {
        val vectors = FakeVectors()
        val self = draft(1, "The user started violin lessons with a teacher").also(vectors::store)
        val first = ScannerMatchEngine.lookupProposal(self, listOf(pending), emptyList(), emptyList(), vectors)
        assertEquals(MatchStatus.PENDING, first.matches.single().status)
        // Approved and later archived: the next lookup reports Archived.
        val archivedNow = pending.copy(status = MatchStatus.ARCHIVED)
        val second = ScannerMatchEngine.lookupProposal(self, listOf(archivedNow), emptyList(), emptyList(), vectors)
        assertEquals(MatchStatus.ARCHIVED, second.matches.single().status)
        // Deleted: it is no longer an eligible record, so nothing refers to it.
        val third = ScannerMatchEngine.lookupProposal(self, emptyList(), listOf("p1"), emptyList(), vectors)
        assertTrue(third.matches.isEmpty() && third.links.isEmpty())
    }

    @Test fun sectionCluesAreReportedOnlyWhenStillEligible() {
        val evidence = ScannerMatchEngine.lookupSection(
            fingerprint = "s", sectionText = "Assistant: anything else today?", windows = emptyList(),
            records = listOf(active, archived),
            clueIds = listOf("a1", "r1", "deleted-memory"), vectors = null
        )
        assertEquals(
            listOf(
                ScannerMatch("a1", null, MatchStatus.ACTIVE, MatchMethod.CHAT_CLUE),
                ScannerMatch("r1", null, MatchStatus.ARCHIVED, MatchMethod.CHAT_CLUE)
            ),
            evidence.matches
        )
    }

    @Test fun aSectionMatchNeedsTheSimilarityAndASharedWord() {
        val vectors = FakeVectors()
        listOf(active, pending).forEach(vectors::store)
        // The window mentions violin and so does the vector; "lessons" is shared only with p1.
        val text = "I finally signed up for lessons, violin of course"
        val evidence = ScannerMatchEngine.lookupSection(
            "s", text, listOf(text), listOf(active, pending), emptyList(), vectors
        )
        val keys = evidence.matches.map { it.key }
        assertTrue("m:p1" in keys)
        assertTrue("m:a1" in keys) // shares "violin"
        evidence.matches.forEach { assertEquals(MatchMethod.EMBEDDING, it.method) }

        // Same direction ("move" inside "removed") but no informative word in
        // common: not a section match.
        val shelf = memory("a2", "The user removed the shelf", MatchStatus.ACTIVE).also(vectors::store)
        val moving = "We move tomorrow"
        val none = ScannerMatchEngine.lookupSection("s2", moving, listOf(moving), listOf(shelf), emptyList(), vectors)
        assertTrue(none.matches.isEmpty())
        assertEquals(0, none.unexamined)
    }

    @Test fun aRecordQuotedWholeInTheSectionIsExact() {
        val evidence = ScannerMatchEngine.lookupSection(
            "s", "Remember: the user plays the violin every evening. Thanks!", emptyList(),
            listOf(active), emptyList(), null
        )
        assertEquals(MatchMethod.EXACT, evidence.matches.single().method)
    }

    @Test fun longTurnEndIsSearchedThroughItsLastWindow() {
        val vectors = FakeVectors()
        vectors.store(pending)
        val filler = (1..400).joinToString(" ") { "word$it" }
        val text = "$filler and at the very end I booked violin lessons with the teacher"
        // A query allowance of 60 "tokens" (one per word plus 2) needs many windows.
        val windows = ScannerQueryWindows.split(text, 60) { it.split(" ").size + 2 }
        assertTrue(windows.size > 8)
        var batches = 0
        val evidence = ScannerMatchEngine.lookupSection(
            "s", text, windows, listOf(pending), emptyList(), vectors,
            ScannerMatchEngine.Limits(maxWindowsPerBatch = 8), checkActive = { batches++ }
        )
        assertEquals(windows.size, vectors.queries.size)
        assertTrue("windows are read in batches", batches >= windows.size / 8)
        assertEquals("m:p1", evidence.matches.single().key)
        assertEquals(MatchMethod.EMBEDDING, evidence.matches.single().method)
    }

    @Test fun aSectionWhoseWindowsCannotAllBeReadMakesNoMeaningClaim() {
        val vectors = object : ComparisonVectors {
            override val modelKey = "m"
            override fun known(records: List<ComparisonRecord>) = emptyMap<String, FloatArray>()
            override fun generate(record: ComparisonRecord): FloatArray? = null
            override fun query(text: String): FloatArray? = null
        }
        val evidence = ScannerMatchEngine.lookupSection(
            "s", "violin lessons teacher", listOf("violin lessons teacher"), listOf(pending), emptyList(), vectors
        )
        assertNull(evidence.modelKey)
        assertEquals(1, evidence.unexamined)
        assertEquals(MatchMethod.KEYWORD, evidence.matches.single().method)
    }
}
