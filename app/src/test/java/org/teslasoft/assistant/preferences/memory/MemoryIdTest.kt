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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The permanent-identity format contract ([MemoryId]).
 *
 * The suite pins the two things the owner's id-hardening ruling requires and
 * that the rest of the system now trusts: new creation always yields the
 * canonical format for its family, and the strict canonical gate is kept
 * separate from the lenient legacy-recognition gate so "new creation" can never
 * quietly start accepting arbitrary strings.
 */
class MemoryIdTest {

    /* ---- generation: canonical, unique, per-family shape ------------------ */

    @Test
    fun twoGeneratedAssociativeIdsDiffer() {
        val a = MemoryId.generate(MemoryId.Type.ASSOCIATIVE)
        val b = MemoryId.generate(MemoryId.Type.ASSOCIATIVE)
        assertNotEquals(a, b)
        assertTrue(a.startsWith("m-"))
        assertTrue(MemoryId.isCanonical(a, MemoryId.Type.ASSOCIATIVE))
        assertTrue(MemoryId.isCanonical(b, MemoryId.Type.ASSOCIATIVE))
    }

    @Test
    fun twoGeneratedLorebookIdsDiffer() {
        val a = MemoryId.generate(MemoryId.Type.LOREBOOK)
        val b = MemoryId.generate(MemoryId.Type.LOREBOOK)
        assertNotEquals(a, b)
        assertFalse(a.startsWith("m-"))
        assertTrue(MemoryId.isCanonical(a, MemoryId.Type.LOREBOOK))
        assertTrue(MemoryId.isCanonical(b, MemoryId.Type.LOREBOOK))
    }

    @Test
    fun everyGeneratedIdIsCanonicalForItsType() {
        repeat(200) {
            assertTrue(MemoryId.isCanonical(MemoryId.generate(MemoryId.Type.ASSOCIATIVE), MemoryId.Type.ASSOCIATIVE))
            assertTrue(MemoryId.isCanonical(MemoryId.generate(MemoryId.Type.LOREBOOK), MemoryId.Type.LOREBOOK))
        }
    }

    /* ---- the canonical gate rejects malformed / cross-family ids ---------- */

    @Test
    fun malformedAssociativeIdsAreRejected() {
        val bad = listOf(
            null,
            "",
            "   ",
            "m-",                                   // prefix, no uuid
            "m-not-a-uuid",
            "b6d2f0e2-1c3a-4b5c-8d7e-9f0a1b2c3d4e", // valid uuid, missing prefix
            "M-b6d2f0e2-1c3a-4b5c-8d7e-9f0a1b2c3d4e", // wrong-case prefix
            "m-b6d2f0e2-1c3a-4b5c-8d7e-9f0a1b2c3d4e-extra",
            "m- b6d2f0e2-1c3a-4b5c-8d7e-9f0a1b2c3d4e"
        )
        for (id in bad) {
            assertFalse("expected non-canonical: $id", MemoryId.isCanonical(id, MemoryId.Type.ASSOCIATIVE))
        }
    }

    @Test
    fun lorebookGateRejectsAssociativePrefixedIds() {
        // A well-formed associative id must not pass as a canonical lorebook id.
        val assoc = MemoryId.generate(MemoryId.Type.ASSOCIATIVE)
        assertFalse(MemoryId.isCanonical(assoc, MemoryId.Type.LOREBOOK))
    }

    @Test
    fun requireCanonicalThrowsOnMalformedAndReturnsOnValid() {
        val good = MemoryId.generate(MemoryId.Type.ASSOCIATIVE)
        assertEquals(good, MemoryId.requireCanonical(good, MemoryId.Type.ASSOCIATIVE, "test"))
        try {
            MemoryId.requireCanonical("m-not-a-uuid", MemoryId.Type.ASSOCIATIVE, "test")
            fail("expected IllegalArgumentException for malformed id")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    /* ---- recognition is lenient (preservation paths) but not blank -------- */

    @Test
    fun recognitionAcceptsCanonicalAndLegacyButNotBlank() {
        val canonical = MemoryId.generate(MemoryId.Type.ASSOCIATIVE)
        assertTrue(MemoryId.isRecognized(canonical, MemoryId.Type.ASSOCIATIVE))
        // A grandfathered legacy shape an existing record already carries.
        assertTrue(MemoryId.isRecognized("legacy-hash-9f0a1b2c3d4e", MemoryId.Type.ASSOCIATIVE))
        assertTrue(MemoryId.isRecognized("b6d2f0e2-1c3a-4b5c-8d7e-9f0a1b2c3d4e", MemoryId.Type.ASSOCIATIVE))
        assertFalse(MemoryId.isRecognized(null, MemoryId.Type.ASSOCIATIVE))
        assertFalse(MemoryId.isRecognized("", MemoryId.Type.ASSOCIATIVE))
        assertFalse(MemoryId.isRecognized("   ", MemoryId.Type.ASSOCIATIVE))
    }
}
