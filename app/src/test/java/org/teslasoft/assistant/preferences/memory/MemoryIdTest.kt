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
 * The suite pins what the owner's id-hardening ruling requires and that the rest
 * of the system now trusts: new creation always yields the canonical format for
 * its family, and the canonical gate is strict — uppercase, short-field, legacy,
 * cross-family, and malformed ids are all rejected so "new creation" can never
 * quietly start accepting arbitrary strings. Legacy preservation is evidence-
 * based at the call site, so this object exposes no permissive recognizer.
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
    fun malformedLorebookIdsAreRejected() {
        val bad = listOf(
            null,
            "",
            "   ",
            "not-a-uuid",
            "m-b6d2f0e2-1c3a-4b5c-8d7e-9f0a1b2c3d4e", // associative-prefixed
            "b6d2f0e2-1c3a-4b5c-8d7e-9f0a1b2c3d4e-extra",
            "b6d2f0e2 1c3a 4b5c 8d7e 9f0a1b2c3d4e"
        )
        for (id in bad) {
            assertFalse("expected non-canonical: $id", MemoryId.isCanonical(id, MemoryId.Type.LOREBOOK))
        }
    }

    @Test
    fun lorebookGateRejectsAssociativePrefixedIds() {
        // A well-formed associative id must not pass as a canonical lorebook id.
        val assoc = MemoryId.generate(MemoryId.Type.ASSOCIATIVE)
        assertFalse(MemoryId.isCanonical(assoc, MemoryId.Type.LOREBOOK))
    }

    @Test
    fun uppercaseUuidTextIsNotCanonical() {
        // Canonical is exactly the lowercase UUID text UUID.toString emits, even
        // though UUID.fromString would happily parse the uppercase form.
        val lowerAssoc = MemoryId.generate(MemoryId.Type.ASSOCIATIVE)
        val upperAssoc = "m-" + lowerAssoc.removePrefix("m-").uppercase()
        assertFalse(MemoryId.isCanonical(upperAssoc, MemoryId.Type.ASSOCIATIVE))

        val lowerLore = MemoryId.generate(MemoryId.Type.LOREBOOK)
        assertFalse(MemoryId.isCanonical(lowerLore.uppercase(), MemoryId.Type.LOREBOOK))
    }

    @Test
    fun shortFieldUuidTextIsNotCanonical() {
        // UUID.fromString accepts "1-1-1-1-1"; canonical requires full 8-4-4-4-12.
        assertFalse(MemoryId.isCanonical("m-1-1-1-1-1", MemoryId.Type.ASSOCIATIVE))
        assertFalse(MemoryId.isCanonical("1-1-1-1-1", MemoryId.Type.LOREBOOK))
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

}
