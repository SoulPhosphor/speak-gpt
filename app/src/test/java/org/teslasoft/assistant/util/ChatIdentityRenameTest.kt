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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rename and creation semantics under stable chat ids (plan commit 3): a
 * rename never changes the id, an unverified (unmarked) entry can never be
 * renamed, and every new chat is born with a minted id plus the identity
 * marker.
 */
class ChatIdentityRenameTest {

    private fun stableEntry(name: String, id: String = "c-1234"): HashMap<String, String> {
        val map = HashMap<String, String>()
        map["name"] = name
        map["id"] = id
        map[ChatIdentity.KEY_IDENTITY_VERSION] = ChatIdentity.IDENTITY_VERSION_STABLE
        return map
    }

    private fun legacyMarkedEntry(name: String): HashMap<String, String> {
        val map = HashMap<String, String>()
        map["name"] = name
        map["id"] = Hash.hash(name)
        map[ChatIdentity.KEY_IDENTITY_VERSION] = ChatIdentity.IDENTITY_VERSION_STABLE
        return map
    }

    @Test
    fun renameOfAStableEntryIsAllowedAndNameOnly() {
        val entry = legacyMarkedEntry("Old title")
        val idBefore = ChatIdentity.effectiveId(entry)
        val entries = listOf(entry, stableEntry("Another chat", "c-other"))

        assertEquals(
            ChatIdentity.RenameDecision.OK,
            ChatIdentity.renameDecision(entries, "Old title", "New title")
        )

        // Apply the rename exactly the way editChat does: name only.
        entry["name"] = "New title"
        assertEquals("the id never changes on rename", idBefore, ChatIdentity.effectiveId(entry))
        assertEquals(Hash.hash("Old title"), entry["id"])
        assertTrue("the entry stays stable after the rename", ChatIdentity.isStable(entry))
    }

    @Test
    fun renameOfAnUnmarkedEntryIsRefused() {
        // §7 rule 4 freeze: an unverified stored id must never be renamed —
        // there is no safe id to carry the new name under.
        val mismatched = hashMapOf(
            "name" to "Old title",
            "id" to Hash.hash("Some other name")
        )
        assertEquals(
            ChatIdentity.RenameDecision.NOT_STABLE,
            ChatIdentity.renameDecision(listOf(mismatched), "Old title", "New title")
        )

        // Even an unmarked entry whose id MATCHES its name is refused: healing
        // marks it at startup, and rename semantics act only on the marker.
        val matching = hashMapOf(
            "name" to "Old title",
            "id" to Hash.hash("Old title")
        )
        assertEquals(
            ChatIdentity.RenameDecision.NOT_STABLE,
            ChatIdentity.renameDecision(listOf(matching), "Old title", "New title")
        )
    }

    @Test
    fun renameOntoAnExistingNameIsRefused() {
        val entries = listOf(
            legacyMarkedEntry("Old title"),
            stableEntry("Taken name", "c-other")
        )
        assertEquals(
            ChatIdentity.RenameDecision.DUPLICATE_NAME,
            ChatIdentity.renameDecision(entries, "Old title", "Taken name")
        )
    }

    @Test
    fun renameOfAMissingNameIsNotFound() {
        assertEquals(
            ChatIdentity.RenameDecision.NOT_FOUND,
            ChatIdentity.renameDecision(listOf(stableEntry("A chat")), "No such chat", "New title")
        )
    }

    @Test
    fun duplicateCheckIgnoresTheEntryBeingRenamed() {
        // Identity comparison, not name equality with itself: the entry
        // under rename must never count as its own duplicate.
        val entry = legacyMarkedEntry("Old title")
        assertEquals(
            ChatIdentity.RenameDecision.OK,
            ChatIdentity.renameDecision(listOf(entry), "Old title", "Old title")
        )
    }

    @Test
    fun newChatEntriesCarryAMintedIdAndTheMarker() {
        val id = StableId.newId(ChatIdentity.STABLE_ID_PREFIX)
        val entry = ChatIdentity.newEntry("New chat 1", id, 1234L)

        assertTrue("minted ids carry the c- prefix", id.startsWith(ChatIdentity.STABLE_ID_PREFIX))
        assertEquals("New chat 1", entry["name"])
        assertEquals(id, entry["id"])
        assertEquals("1234", entry["timestamp"])
        assertEquals("false", entry["pinned"])
        assertEquals(ChatIdentity.IDENTITY_VERSION_STABLE, entry[ChatIdentity.KEY_IDENTITY_VERSION])
        assertTrue(ChatIdentity.isStable(entry))
        assertEquals("the resolver returns the minted id verbatim", id, ChatIdentity.effectiveId(entry))
        assertEquals("nothing for healing to do on a new entry", ChatIdentity.HealAction.NONE, ChatIdentity.healAction(entry))
    }
}
