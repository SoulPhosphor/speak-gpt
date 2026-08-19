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
 * record (or a legitimate restore of a deleted one); a birth mismatch — or any
 * ambiguous identity — is refused rather than silently overwriting, and a
 * non-canonical id is refused outright (import requires the canonical format,
 * because a genuine legacy associative id is already canonical).
 */
class MemoryIdImportTest {

    private val type = MemoryId.Type.ASSOCIATIVE
    private val id = MemoryId.generate(type)

    @Test
    fun newIdInserts() {
        assertEquals(
            MemoryIdImport.Disposition.INSERT,
            MemoryIdImport.classify(id, "2026-08-19T00:00:00Z", type, MemoryIdImport.Existing.None)
        )
    }

    @Test
    fun sameLiveRecordIsPreservedNotOverwritten() {
        assertEquals(
            MemoryIdImport.Disposition.PRESERVE_EXISTING,
            MemoryIdImport.classify(
                id, "2026-08-19T00:00:00Z", type,
                MemoryIdImport.Existing.Live("2026-08-19T00:00:00Z")
            )
        )
    }

    @Test
    fun differentLiveRecordSharingIdIsRejected() {
        assertEquals(
            MemoryIdImport.Disposition.REJECT_COLLISION,
            MemoryIdImport.classify(
                id, "2026-08-19T09:00:00Z", type,
                MemoryIdImport.Existing.Live("2026-08-19T00:00:00Z")
            )
        )
    }

    @Test
    fun restoreOfDeletedRecordIsAdmitted() {
        assertEquals(
            MemoryIdImport.Disposition.INSERT,
            MemoryIdImport.classify(
                id, "2026-08-19T00:00:00Z", type,
                MemoryIdImport.Existing.Tombstoned("2026-08-19T00:00:00Z")
            )
        )
    }

    @Test
    fun differentRecordReusingDeletedIdIsRejected() {
        assertEquals(
            MemoryIdImport.Disposition.REJECT_COLLISION,
            MemoryIdImport.classify(
                id, "2026-08-19T09:00:00Z", type,
                MemoryIdImport.Existing.Tombstoned("2026-08-19T00:00:00Z")
            )
        )
    }

    @Test
    fun unknownTombstoneBirthFailsClosed() {
        // A tombstone with no recorded birth cannot prove the incoming record is
        // the same one that was deleted, so the reuse is refused, not admitted.
        assertEquals(
            MemoryIdImport.Disposition.REJECT_COLLISION,
            MemoryIdImport.classify(
                id, "2026-08-19T09:00:00Z", type,
                MemoryIdImport.Existing.Tombstoned(null)
            )
        )
    }

    @Test
    fun nonCanonicalIncomingIdIsRejectedInvalid() {
        // Import requires the canonical format; a genuine legacy associative id
        // is already m-<uuid>, so a non-canonical id is malformed, not legacy.
        for (bad in listOf(null, "", "   ", "m-not-a-uuid", "legacy-id-42",
            "b6d2f0e2-1c3a-4b5c-8d7e-9f0a1b2c3d4e")) {
            assertEquals(
                "expected REJECT_INVALID for $bad",
                MemoryIdImport.Disposition.REJECT_INVALID,
                MemoryIdImport.classify(bad, "2026-08-19T00:00:00Z", type, MemoryIdImport.Existing.None)
            )
        }
    }

    @Test
    fun missingIncomingBirthAgainstLiveIsCollisionNotPreserve() {
        // A record with no birth timestamp cannot prove it is the same record.
        assertEquals(
            MemoryIdImport.Disposition.REJECT_COLLISION,
            MemoryIdImport.classify(id, null, type, MemoryIdImport.Existing.Live("2026-08-19T00:00:00Z"))
        )
    }
}
