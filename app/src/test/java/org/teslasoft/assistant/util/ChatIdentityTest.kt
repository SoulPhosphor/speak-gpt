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

package org.teslasoft.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resolver decision table from chat-id-stable-identity-plan.md §6. Every
 * chat-list read site funnels through ChatIdentity.effectiveId; these cases
 * pin the exact authority rules so a "helpful" simplification (e.g. inferring
 * stability from id == hash(name)) fails loudly.
 */
class ChatIdentityTest {

    private fun entry(
        name: String? = "My chat",
        id: String? = null,
        marker: String? = null
    ): HashMap<String, String> {
        val map = HashMap<String, String>()
        if (name != null) map["name"] = name
        if (id != null) map["id"] = id
        if (marker != null) map[ChatIdentity.KEY_IDENTITY_VERSION] = marker
        return map
    }

    @Test
    fun markedEntryReturnsStoredIdVerbatim() {
        val e = entry(name = "Renamed since", id = Hash.hash("Original name"), marker = "2")
        assertTrue(ChatIdentity.isStable(e))
        assertEquals(
            "a marked entry keeps its stored id even though it no longer matches the name hash",
            Hash.hash("Original name"), ChatIdentity.effectiveId(e)
        )
    }

    @Test
    fun cPrefixedIdIsTrustedWithoutTheMarker() {
        val e = entry(id = "c-1234-abcd")
        assertTrue("a c- id can only have been minted by the new code", ChatIdentity.isStable(e))
        assertEquals("c-1234-abcd", ChatIdentity.effectiveId(e))
    }

    @Test
    fun unmarkedMatchingEntryResolvesToTheNameHash() {
        val e = entry(name = "My chat", id = Hash.hash("My chat"))
        assertFalse(ChatIdentity.isStable(e))
        assertEquals(Hash.hash("My chat"), ChatIdentity.effectiveId(e))
    }

    @Test
    fun unmarkedMismatchedEntryResolvesToTheNameHashNotTheStoredId() {
        // §7 rule 4: the resolver keeps a mismatched legacy entry on the
        // name-derived id so the user keeps seeing exactly what they see
        // today; the stored id is never silently adopted.
        val e = entry(name = "My chat", id = Hash.hash("Some other name"))
        assertFalse(ChatIdentity.isStable(e))
        assertEquals(Hash.hash("My chat"), ChatIdentity.effectiveId(e))
    }

    @Test
    fun blankOrAbsentIdFallsBackToTheNameHash() {
        assertEquals(Hash.hash("My chat"), ChatIdentity.effectiveId(entry(name = "My chat", id = null)))
        assertEquals(Hash.hash("My chat"), ChatIdentity.effectiveId(entry(name = "My chat", id = "")))
    }

    @Test
    fun markerWithBlankIdIsNotStable() {
        // A marked entry with no id has nothing to return; it stays on the
        // legacy fallback until healing repairs the id, never resolves to "".
        val e = entry(name = "My chat", id = "", marker = "2")
        assertFalse(ChatIdentity.isStable(e))
        assertEquals(Hash.hash("My chat"), ChatIdentity.effectiveId(e))
    }

    @Test
    fun absentNameHashesItsLegacyStringForm() {
        // Legacy sites computed Hash.hash(entry["name"].toString()) — a null
        // name hashed the string "null". The fallback stays byte-identical.
        val e = entry(name = null, id = null)
        assertEquals(Hash.hash("null"), ChatIdentity.effectiveId(e))
    }

    @Test
    fun unknownMarkerValueDoesNotConferStability() {
        val e = entry(name = "My chat", id = Hash.hash("Elsewhere"), marker = "3")
        assertFalse(ChatIdentity.isStable(e))
        assertEquals(Hash.hash("My chat"), ChatIdentity.effectiveId(e))
    }
}
