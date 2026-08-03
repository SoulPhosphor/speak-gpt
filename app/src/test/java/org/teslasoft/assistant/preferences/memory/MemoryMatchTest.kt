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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Possible Match detection cases (Step 1.5, owner-approved hygiene rules).
 * Pure JVM — the store itself is SQLCipher and is exercised on device.
 */
class MemoryMatchTest {

    private fun candidate(
        content: String, scope: String = "real_life", kind: String = "fact",
        targets: List<String> = emptyList()
    ) = MemoryMatch.Candidate(content, scope, kind, targets)

    private fun existing(
        id: String, content: String, scope: String = "real_life", kind: String = "fact",
        status: String = "active", targets: List<String> = emptyList()
    ) = MemoryMatch.Existing(id, content, scope, kind, status, targets)

    /* ------------------------- exact identity ------------------------- */

    @Test
    fun exactSameContentPlacementKind_active_isAlreadyPresent() {
        val c = candidate("Her birthday is in May.")
        val lib = listOf(existing("m1", "Her birthday is in May."))
        assertEquals(MemoryMatch.Outcome.AlreadyPresent, MemoryMatch.classify(c, lib))
    }

    @Test
    fun exactSameContentPlacementKind_pendingDraft_isAlreadyPresent() {
        // A second identical pending draft must not be created either.
        val c = candidate("Her birthday is in May.")
        val lib = listOf(existing("m1", "Her birthday is in May.", status = "draft"))
        assertEquals(MemoryMatch.Outcome.AlreadyPresent, MemoryMatch.classify(c, lib))
    }

    @Test
    fun titleIsExcludedFromIdentity() {
        // Candidate has no title at all; content match alone suppresses.
        val c = candidate("Prefers tea to coffee.")
        val lib = listOf(existing("m1", "Prefers tea to coffee."))
        assertEquals(MemoryMatch.Outcome.AlreadyPresent, MemoryMatch.classify(c, lib))
    }

    @Test
    fun normalizationIgnoresCaseAndWhitespaceButNotNegation() {
        assertEquals(
            MemoryMatch.normalizeContent("Loves   the\nBEACH"),
            MemoryMatch.normalizeContent("loves the beach")
        )
        // Negation is preserved: these do not normalize to the same string.
        assertTrue(
            MemoryMatch.normalizeContent("likes olives") !=
                MemoryMatch.normalizeContent("no longer likes olives")
        )
    }

    @Test
    fun targetOrderDoesNotAffectExactIdentity() {
        val c = candidate("A landmark stands here.", scope = "world", targets = listOf("w2", "w1"))
        val lib = listOf(existing("m1", "A landmark stands here.", scope = "world", targets = listOf("w1", "w2")))
        assertEquals(MemoryMatch.Outcome.AlreadyPresent, MemoryMatch.classify(c, lib))
    }

    /* ------------------- exact but must be reviewed ------------------- */

    @Test
    fun exactContentDifferentKind_isPossibleMatch() {
        val c = candidate("Do not mention her father.", kind = "instruction")
        val lib = listOf(existing("m1", "Do not mention her father.", kind = "fact"))
        val out = MemoryMatch.classify(c, lib)
        assertTrue(out is MemoryMatch.Outcome.Possible)
        assertEquals(MemoryMatch.Relation.EXACT_DIFFERENT_KIND, (out as MemoryMatch.Outcome.Possible).matches.single().relation)
    }

    @Test
    fun exactContentArchived_isPossibleMatch() {
        val c = candidate("Works at the hospital.")
        val lib = listOf(existing("m1", "Works at the hospital.", status = "archived"))
        val out = MemoryMatch.classify(c, lib)
        assertTrue(out is MemoryMatch.Outcome.Possible)
        assertEquals(MemoryMatch.Relation.EXACT_INACTIVE, (out as MemoryMatch.Outcome.Possible).matches.single().relation)
    }

    @Test
    fun exactContentSuperseded_isPossibleMatch() {
        val c = candidate("Works at the hospital.")
        val lib = listOf(existing("m1", "Works at the hospital.", status = "superseded"))
        val out = MemoryMatch.classify(c, lib)
        assertTrue(out is MemoryMatch.Outcome.Possible)
        assertEquals(MemoryMatch.Relation.EXACT_INACTIVE, (out as MemoryMatch.Outcome.Possible).matches.single().relation)
    }

    /* -------------------------- near matches -------------------------- */

    @Test
    fun negationContradiction_isPossibleMatchNotDuplicate() {
        val c = candidate("She no longer likes pineapple pizza.")
        val lib = listOf(existing("m1", "She likes pineapple pizza."))
        val out = MemoryMatch.classify(c, lib)
        assertTrue(out is MemoryMatch.Outcome.Possible)
        assertEquals(MemoryMatch.Relation.SEMANTIC_NEAR, (out as MemoryMatch.Outcome.Possible).matches.single().relation)
    }

    @Test
    fun unrelatedContent_isUnique() {
        val c = candidate("The garden needs watering twice weekly.")
        val lib = listOf(existing("m1", "Her favorite composer is Chopin."))
        assertEquals(MemoryMatch.Outcome.Unique, MemoryMatch.classify(c, lib))
    }

    @Test
    fun emptyLibrary_isUnique() {
        assertEquals(MemoryMatch.Outcome.Unique, MemoryMatch.classify(candidate("Anything."), emptyList()))
    }

    /* ------------------------ the fiction wall ------------------------ */

    @Test
    fun identicalTextInDifferentWorlds_isUnique() {
        val c = candidate("A dragon guards the pass.", scope = "world", targets = listOf("w1"))
        val lib = listOf(existing("m1", "A dragon guards the pass.", scope = "world", targets = listOf("w2")))
        assertEquals(MemoryMatch.Outcome.Unique, MemoryMatch.classify(c, lib))
    }

    @Test
    fun nearTextSharingAWorld_isPossibleMatch() {
        val c = candidate(
            "A great dragon guards the mountain pass.", scope = "world", targets = listOf("w1")
        )
        val lib = listOf(
            existing("m1", "A dragon guards the mountain pass.", scope = "world", targets = listOf("w1", "w2"))
        )
        val out = MemoryMatch.classify(c, lib)
        assertTrue(out is MemoryMatch.Outcome.Possible)
        assertEquals(MemoryMatch.Relation.SEMANTIC_NEAR, (out as MemoryMatch.Outcome.Possible).matches.single().relation)
    }

    /* -------------------------- precedence --------------------------- */

    @Test
    fun alreadyPresentWinsOverOtherPossibleMatches() {
        val c = candidate("Allergic to shellfish.")
        val lib = listOf(
            existing("m1", "Allergic to shellfish once nearly hospitalized her.", status = "archived"),
            existing("m2", "Allergic to shellfish.") // exact active, same kind
        )
        assertEquals(MemoryMatch.Outcome.AlreadyPresent, MemoryMatch.classify(c, lib))
    }

    @Test
    fun multiplePossibleMatchesAreAllReturned() {
        val c = candidate("Her sister lives in Denver now.")
        val lib = listOf(
            existing("m1", "Her sister lives in Denver.", status = "archived"),
            existing("m2", "Her sister now lives in Denver.")
        )
        val out = MemoryMatch.classify(c, lib)
        assertTrue(out is MemoryMatch.Outcome.Possible)
        assertEquals(2, (out as MemoryMatch.Outcome.Possible).matches.size)
    }

    /* --------------------------- helpers ----------------------------- */

    @Test
    fun placementKeyIsOrderIndependent() {
        assertEquals(
            MemoryMatch.placementKey("world", listOf("a", "b")),
            MemoryMatch.placementKey("world", listOf("b", "a"))
        )
    }

    @Test
    fun comparablePlacementRespectsScopeAndTargetOverlap() {
        assertTrue(MemoryMatch.comparablePlacement("global", emptyList(), "global", emptyList()))
        assertTrue(!MemoryMatch.comparablePlacement("global", emptyList(), "real_life", emptyList()))
        assertTrue(MemoryMatch.comparablePlacement("world", listOf("w1"), "world", listOf("w1", "w2")))
        assertTrue(!MemoryMatch.comparablePlacement("world", listOf("w1"), "world", listOf("w2")))
    }
}
