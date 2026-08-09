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
import org.junit.Test

class PossibleMatchFinderTest {

    @Test
    fun aiHintsAddReviewTargetsWithoutOverridingLocalSafetyRelations() {
        val local = listOf(
            MemoryMatch.Match("same", MemoryMatch.Relation.SEMANTIC_NEAR),
            MemoryMatch.Match("exact", MemoryMatch.Relation.EXACT_INACTIVE)
        )
        val hints = listOf(
            MemoryMatch.Match("same", MemoryMatch.Relation.AI_RELATED),
            MemoryMatch.Match("hint-only", MemoryMatch.Relation.AI_RELATED)
        )

        assertEquals(
            listOf(
                MemoryMatch.Match("same", MemoryMatch.Relation.SEMANTIC_NEAR),
                MemoryMatch.Match("exact", MemoryMatch.Relation.EXACT_INACTIVE),
                MemoryMatch.Match("hint-only", MemoryMatch.Relation.AI_RELATED)
            ),
            PossibleMatchFinder.mergeRelationshipHints(local, hints)
        )
    }
}
