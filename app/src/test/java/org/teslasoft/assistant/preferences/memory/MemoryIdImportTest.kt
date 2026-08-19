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
 * The same-record vs. identity-collision decision ([MemoryIdImport.classify]).
 *
 * Pins the collision-safety contract: an incoming id that already exists is
 * admitted only when the immutable birth timestamp proves it is the SAME logical
 * record (or a legitimate restore of a deleted one); a birth mismatch is refused
 * rather than silently overwriting, and a malformed id is refused outright.
 */
class MemoryIdImportTest {

    private val type = MemoryId.Type.ASSOCIATIVE
    private val id = MemoryId.generate(type)

    @Test
    fun newIdInserts() {
        assertEquals(
            MemoryIdImport.Disposition.INSERT,
            MemoryIdImport.classify(id, "2026-08-19T00:00:00Z", MemoryIdImport.Existing.None)
        )
    }

    @Test
    fun sameLiveRecordIsPreservedNotOverwritten() {
        assertEquals(
            MemoryIdImport.Disposition.PRESERVE_EXISTING,
            MemoryIdImport.classify(
                id, "2026-08-19T00:00:00Z",
                MemoryIdImport.Existing.Live("2026-08-19T00:00:00Z")
            )
        )
    }

    @Test
    fun differentLiveRecordSharingIdIsRejected() {
        assertEquals(
            MemoryIdImport.Disposition.REJECT_COLLISION,
            MemoryIdImport.classify(
                id, "2026-08-19T09:00:00Z",
                MemoryIdImport.Existing.Live("2026-08-19T00:00:00Z")
            )
        )
    }

    @Test
    fun restoreOfDeletedRecordIsAdmitted() {
        assertEquals(
            MemoryIdImport.Disposition.INSERT,
            MemoryIdImport.classify(
                id, "2026-08-19T00:00:00Z",
                MemoryIdImport.Existing.Tombstoned("2026-08-19T00:00:00Z")
            )
        )
    }

    @Test
    fun differentRecordReusingDeletedIdIsRejected() {
        assertEquals(
            MemoryIdImport.Disposition.REJECT_COLLISION,
            MemoryIdImport.classify(
                id, "2026-08-19T09:00:00Z",
                MemoryIdImport.Existing.Tombstoned("2026-08-19T00:00:00Z")
            )
        )
    }

    @Test
    fun unknownTombstoneBirthAdmitsRestoreBestEffort() {
        assertEquals(
            MemoryIdImport.Disposition.INSERT,
            MemoryIdImport.classify(
                id, "2026-08-19T09:00:00Z",
                MemoryIdImport.Existing.Tombstoned(null)
            )
        )
    }

    @Test
    fun blankIncomingIdIsRejectedInvalid() {
        // Only a blank id — which names no identity — is invalid on import.
        assertEquals(
            MemoryIdImport.Disposition.REJECT_INVALID,
            MemoryIdImport.classify(null, "2026-08-19T00:00:00Z", MemoryIdImport.Existing.None)
        )
        assertEquals(
            MemoryIdImport.Disposition.REJECT_INVALID,
            MemoryIdImport.classify("   ", "2026-08-19T00:00:00Z", MemoryIdImport.Existing.None)
        )
    }

    @Test
    fun nonCanonicalLegacyIdIsPreservedNotRejected() {
        // A grandfathered legacy id names a real identity; import preserves it.
        assertEquals(
            MemoryIdImport.Disposition.INSERT,
            MemoryIdImport.classify("legacy-id-42", "2026-08-19T00:00:00Z", MemoryIdImport.Existing.None)
        )
    }

    @Test
    fun missingIncomingBirthAgainstLiveIsCollisionNotPreserve() {
        // A record with no birth timestamp cannot prove it is the same record.
        assertEquals(
            MemoryIdImport.Disposition.REJECT_COLLISION,
            MemoryIdImport.classify(id, null, MemoryIdImport.Existing.Live("2026-08-19T00:00:00Z"))
        )
    }
}
