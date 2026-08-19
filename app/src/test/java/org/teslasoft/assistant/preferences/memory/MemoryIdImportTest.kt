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

/**
 * The merge-import disposition rule ([MemoryIdImport.classify]) — the five cases
 * the repair import distinguishes: clean insert, repair-under-a-new-id (cases
 * 1-3), preserve-identical, and the two conflicts that need the user's intent
 * (cases 4-5).
 */
class MemoryIdImportTest {

    private val type = MemoryId.Type.ASSOCIATIVE
    private val id = MemoryId.generate(type)

    private fun sub(content: String) = MemoryIdImport.Substance(
        content = content, scope = "global", typeId = null, importance = 0,
        tagsJson = "[]", protectionJson = null, modeHintsJson = "[]", status = "active",
        companionIds = emptyList(), entityRefs = emptyList(), worldIds = emptyList(),
        roleplayCharacterIds = emptyList(), campaignIds = emptyList(), projectIds = emptyList()
    )

    private fun classify(
        incomingId: String?, createdAt: String?, content: String, existing: MemoryIdImport.Existing
    ) = MemoryIdImport.classify(incomingId, createdAt, sub(content), type, existing)

    @Test
    fun canonicalNovelIdInserts() {
        assertEquals(
            MemoryIdImport.Disposition.INSERT,
            classify(id, "2026-01-01T00:00:00Z", "x", MemoryIdImport.Existing.None)
        )
    }

    @Test
    fun nonCanonicalOrBlankIdIsRepaired() {
        for (bad in listOf(null, "", "   ", "m-not-a-uuid", "legacy-id-42")) {
            assertEquals(
                "expected INSERT_REMAPPED for $bad",
                MemoryIdImport.Disposition.INSERT_REMAPPED,
                classify(bad, "2026-01-01T00:00:00Z", "x", MemoryIdImport.Existing.None)
            )
        }
    }

    @Test
    fun sameBirthIdenticalSubstanceIsPreserved() {
        assertEquals(
            MemoryIdImport.Disposition.PRESERVE_EXISTING,
            classify(id, "2026-01-01T00:00:00Z", "same",
                MemoryIdImport.Existing.Live("2026-01-01T00:00:00Z", sub("same")))
        )
    }

    @Test
    fun sameBirthDifferentSubstanceIsVersionConflict() {
        assertEquals(
            MemoryIdImport.Disposition.CONFLICT_VERSION,
            classify(id, "2026-01-01T00:00:00Z", "changed",
                MemoryIdImport.Existing.Live("2026-01-01T00:00:00Z", sub("original")))
        )
    }

    @Test
    fun differentBirthAgainstLiveIsRepaired() {
        assertEquals(
            MemoryIdImport.Disposition.INSERT_REMAPPED,
            classify(id, "2026-06-06T00:00:00Z", "x",
                MemoryIdImport.Existing.Live("2026-01-01T00:00:00Z", sub("x")))
        )
    }

    @Test
    fun tombstonedSameBirthIsRestoreConflict() {
        assertEquals(
            MemoryIdImport.Disposition.CONFLICT_RESTORE,
            classify(id, "2026-01-01T00:00:00Z", "x",
                MemoryIdImport.Existing.Tombstoned("2026-01-01T00:00:00Z"))
        )
    }

    @Test
    fun tombstonedDifferentBirthIsRepaired() {
        assertEquals(
            MemoryIdImport.Disposition.INSERT_REMAPPED,
            classify(id, "2026-06-06T00:00:00Z", "x",
                MemoryIdImport.Existing.Tombstoned("2026-01-01T00:00:00Z"))
        )
    }

    @Test
    fun tombstonedUnknownBirthIsRepairedNotRestored() {
        // Unknown tombstone birth cannot prove the same deleted memory, so it is
        // imported under a new id (burned id stays), never auto-restored.
        assertEquals(
            MemoryIdImport.Disposition.INSERT_REMAPPED,
            classify(id, "2026-01-01T00:00:00Z", "x", MemoryIdImport.Existing.Tombstoned(null))
        )
    }

    @Test
    fun substanceIgnoresTargetOrderingButNotContent() {
        val a = MemoryIdImport.substanceOf(
            record(content = "c", worldIds = listOf("w2", "w1"))
        )
        val b = MemoryIdImport.substanceOf(
            record(content = "c", worldIds = listOf("w1", "w2"))
        )
        assertEquals(a, b) // reordered targets are the same substance
        val c = MemoryIdImport.substanceOf(record(content = "different", worldIds = listOf("w1", "w2")))
        assertEquals(false, b == c) // a content change is a different substance
    }

    private fun record(content: String, worldIds: List<String>) = MemoryRecord(
        memoryId = id, scope = "world", content = content, embeddingText = null, tagsJson = "[]",
        importance = 0, worldIds = worldIds, roleplayCharacterIds = emptyList(),
        campaignIds = emptyList(), projectIds = emptyList(), protectionJson = null,
        modeHintsJson = "[]", createdAt = "2026-01-01T00:00:00Z", updatedAt = null,
        status = "active", supersedes = null, companionIds = emptyList(), entityRefs = emptyList(),
        changeLog = emptyList(), origin = "user", typeId = null
    )
}
