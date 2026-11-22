/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.MemoryMatch.Relation
import org.teslasoft.assistant.preferences.memory.PendingMatchPresentation.Method

class PendingMatchPresentationTest {

    private val statuses = mapOf(
        "exact" to "archived", "alt" to "draft", "pendingHint" to "draft",
        "simLow" to "active", "simHigh" to "superseded", "scanner" to "active"
    )

    private fun order(matches: List<MemoryMatch.Match>, linksBack: Set<String> = setOf("alt")) =
        PendingMatchPresentation.order(matches, { statuses[it] }, { it in linksBack })

    private val all = listOf(
        MemoryMatch.Match("scanner", Relation.AI_RELATED),
        MemoryMatch.Match("simLow", Relation.SEMANTIC_NEAR, 0.82f),
        MemoryMatch.Match("alt", Relation.AI_RELATED),
        MemoryMatch.Match("simHigh", Relation.SEMANTIC_NEAR, 0.93f),
        MemoryMatch.Match("exact", Relation.EXACT_INACTIVE)
    )

    @Test
    fun orderIsExactThenAlternativeThenHighestSimilarityThenScannerLinks() {
        assertEquals(
            listOf("exact", "alt", "simHigh", "simLow", "scanner"),
            order(all).map { it.memoryId }
        )
    }

    @Test
    fun methodsAndStatusesComeFromTheEvidence() {
        val byId = order(all).associateBy { it.memoryId }
        assertEquals(Method.EXACT, byId.getValue("exact").method)
        assertEquals("archived", byId.getValue("exact").status)
        assertEquals(Method.ALTERNATIVE, byId.getValue("alt").method)
        assertEquals(Method.SIMILARITY, byId.getValue("simHigh").method)
        assertEquals("superseded", byId.getValue("simHigh").status)
        assertEquals(Method.SCANNER, byId.getValue("scanner").method)
    }

    @Test
    fun onlySimilarityMatchesCarryAScore() {
        val presented = order(all + MemoryMatch.Match("ghostScore", Relation.AI_RELATED, 0.99f))
        presented.forEach {
            if (it.method == Method.SIMILARITY) assertTrue(it.score != null) else assertNull(it.score)
        }
        assertEquals(0.93f, presented.first { it.memoryId == "simHigh" }.score!!, 0f)
    }

    @Test
    fun aPendingLinkIsAnAlternativeOnlyWhenItLinksBack() {
        val presented = order(listOf(MemoryMatch.Match("pendingHint", Relation.AI_RELATED)), linksBack = emptySet())
        assertEquals(Method.SCANNER, presented.single().method)
        // An Active memory can never be an alternative suggestion.
        assertEquals(Method.SCANNER, order(listOf(MemoryMatch.Match("scanner", Relation.AI_RELATED)), setOf("scanner")).single().method)
    }

    @Test
    fun deletedMatchesAndDuplicatesAreLeftOut() {
        val presented = order(listOf(
            MemoryMatch.Match("gone", Relation.SEMANTIC_NEAR, 0.9f),
            MemoryMatch.Match("exact", Relation.EXACT_INACTIVE),
            MemoryMatch.Match("exact", Relation.AI_RELATED)
        ))
        assertEquals(listOf("exact"), presented.map { it.memoryId })
        assertEquals(Method.EXACT, presented.single().method)
    }

    @Test
    fun cardSummaryShowsTheClosestAndCountsTheRest() {
        val summary = PendingMatchPresentation.summary(order(all))!!
        assertEquals("exact", summary.closest.memoryId)
        assertEquals(4, summary.additionalCount)
        assertNull(PendingMatchPresentation.summary(emptyList()))
    }

    @Test
    fun openingATappedMatchSelectsExactlyThatPairAndPreselectsOnlyIt() {
        val opened = PendingMatchPresentation.open(order(all), "simLow")!!
        assertEquals("simLow", opened.selected.memoryId)
        assertEquals(listOf("exact", "alt", "simHigh", "scanner"), opened.additional.map { it.memoryId })
        assertFalse(opened.requestedMissing)
        assertEquals(setOf("simLow"), opened.preselected)
    }

    @Test
    fun reviewButtonOpensTheClosestMatch() {
        val opened = PendingMatchPresentation.open(order(all), null)!!
        assertEquals("exact", opened.selected.memoryId)
        assertEquals(setOf("exact"), opened.preselected)
    }

    @Test
    fun pendingMatchesAreReviewOnlyAndNeverPreselected() {
        val opened = PendingMatchPresentation.open(order(all), "alt")!!
        assertEquals("alt", opened.selected.memoryId)
        assertTrue(opened.selected.reviewOnly)
        assertTrue(opened.preselected.isEmpty())
        assertFalse(order(all).first { it.memoryId == "exact" }.reviewOnly)
    }

    @Test
    fun aTappedMatchThatIsGoneShowsTheCurrentMatchesWithoutPreselecting() {
        val opened = PendingMatchPresentation.open(order(all), "deletedOrNoLongerMatching")!!
        assertTrue(opened.requestedMissing)
        assertEquals("exact", opened.selected.memoryId)
        assertTrue(opened.preselected.isEmpty())
        assertNull(PendingMatchPresentation.open(emptyList(), "anything"))
    }

    @Test
    fun reviewFingerprintChangesWithAnyUserVisibleEditButNotDerivedText() {
        val base = record()
        val fp = PendingMatchPresentation.reviewFingerprint(base)
        assertEquals(fp, PendingMatchPresentation.reviewFingerprint(base.copy(embeddingText = "derived", updatedAt = "later")))
        listOf(
            base.copy(content = "changed"), base.copy(status = "archived"), base.copy(importance = 2),
            base.copy(tagsJson = "[\"x\"]"), base.copy(scope = "global"), base.copy(worldIds = listOf("w2")),
            base.copy(companionIds = listOf("c")), base.copy(typeId = "t"), base.copy(vibe = "v"), base.copy(title = "t")
        ).forEach { assertNotEquals(fp, PendingMatchPresentation.reviewFingerprint(it)) }
    }

    private fun record() = MemoryRecord(
        memoryId = "m", scope = "world", content = "Likes tea", embeddingText = null, tagsJson = "[]",
        importance = 0, worldIds = listOf("w1"), roleplayCharacterIds = emptyList(),
        campaignIds = emptyList(), projectIds = emptyList(), protectionJson = null,
        modeHintsJson = "[]", createdAt = "2026-01-01T00:00:00Z", updatedAt = null,
        status = "active", supersedes = null, companionIds = emptyList(), entityRefs = emptyList(),
        changeLog = emptyList(), origin = "user", typeId = null
    )
}
