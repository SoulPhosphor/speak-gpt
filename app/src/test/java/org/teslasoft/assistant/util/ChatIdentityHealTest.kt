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

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Type

/**
 * The startup healing rules from chat-id-stable-identity-plan.md §7. Rule 4
 * is the load-bearing one: a mismatched stored id gets NO repair — the
 * rejected repair machinery (file probing, history switching, journaled
 * moves) must never be reintroduced, and this test pins the decision that
 * keeps it out.
 */
class ChatIdentityHealTest {

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

    // §7 rule 1: stored id matches the name hash -> marker only.
    @Test
    fun matchingStoredIdGetsTheMarker() {
        val e = entry(id = Hash.hash("My chat"))
        assertEquals(ChatIdentity.HealAction.ADD_MARKER, ChatIdentity.healAction(e))
    }

    // §7 rule 2: blank/absent id -> fill with the name hash, then mark.
    @Test
    fun blankOrAbsentIdIsFilledAndMarked() {
        assertEquals(ChatIdentity.HealAction.SET_ID_AND_MARK, ChatIdentity.healAction(entry(id = null)))
        assertEquals(ChatIdentity.HealAction.SET_ID_AND_MARK, ChatIdentity.healAction(entry(id = "")))
    }

    // §7 rule 3: a minted c- id converges to marked even without the marker.
    @Test
    fun mintedIdWithoutMarkerConverges() {
        assertEquals(ChatIdentity.HealAction.ADD_MARKER, ChatIdentity.healAction(entry(id = "c-1234")))
    }

    // §7 rule 4: mismatch -> NO repair, entry stays unmarked.
    @Test
    fun mismatchedStoredIdIsNeverRepaired() {
        val e = entry(name = "My chat", id = Hash.hash("A different name"))
        assertEquals(ChatIdentity.HealAction.MISMATCH_NO_REPAIR, ChatIdentity.healAction(e))
    }

    @Test
    fun markedEntryIsSkipped() {
        val e = entry(id = Hash.hash("Anything at all"), marker = "2")
        assertEquals(ChatIdentity.HealAction.NONE, ChatIdentity.healAction(e))
    }

    @Test
    fun markedEntryWithBlankIdIsRepairedNotSkipped() {
        val e = entry(id = "", marker = "2")
        assertEquals(ChatIdentity.HealAction.SET_ID_AND_MARK, ChatIdentity.healAction(e))
    }

    @Test
    fun nonAuthoritativeListProducesAnEmptyPlan() {
        val entries = listOf(entry(id = Hash.hash("My chat")), entry(id = null))
        assertTrue(
            "a masked (locked/corrupt) list view must never mint permanent identity",
            ChatIdentity.planHealing(entries, authoritative = false).isEmpty()
        )
        assertEquals(2, ChatIdentity.planHealing(entries, authoritative = true).size)
    }

    /** Applying an action the way ChatPreferences.healChatIdentityMarkers does. */
    private fun apply(e: HashMap<String, String>, action: ChatIdentity.HealAction) {
        when (action) {
            ChatIdentity.HealAction.ADD_MARKER ->
                e[ChatIdentity.KEY_IDENTITY_VERSION] = ChatIdentity.IDENTITY_VERSION_STABLE
            ChatIdentity.HealAction.SET_ID_AND_MARK -> {
                e["id"] = ChatIdentity.effectiveId(e)
                e[ChatIdentity.KEY_IDENTITY_VERSION] = ChatIdentity.IDENTITY_VERSION_STABLE
            }
            else -> { /* NONE and MISMATCH_NO_REPAIR change nothing */ }
        }
    }

    @Test
    fun healingIsIdempotent() {
        val healable = listOf(
            entry(id = Hash.hash("My chat")),
            entry(id = null),
            entry(id = "c-1234")
        )
        for (e in healable) {
            apply(e, ChatIdentity.healAction(e))
            assertTrue("healed entry is stable", ChatIdentity.isStable(e))
            assertEquals("second run is a no-op", ChatIdentity.HealAction.NONE, ChatIdentity.healAction(e))
        }

        val mismatch = entry(name = "My chat", id = Hash.hash("A different name"))
        apply(mismatch, ChatIdentity.healAction(mismatch))
        assertEquals(
            "a mismatched entry stays unmarked and is re-reported every start, never half-healed",
            ChatIdentity.HealAction.MISMATCH_NO_REPAIR, ChatIdentity.healAction(mismatch)
        )
    }

    @Test
    fun healedIdenticalIdIsTheOneReadersAlreadyResolved() {
        val e = entry(name = "My chat", id = null)
        val before = ChatIdentity.effectiveId(e)
        apply(e, ChatIdentity.healAction(e))
        assertEquals("healing changes authority, never the id readers see", before, ChatIdentity.effectiveId(e))
        assertEquals(Hash.hash("My chat"), e["id"])
    }

    @Test
    fun markerSurvivesAGsonRoundTrip() {
        // The marker travels inside the same JSON blob as the other entry
        // keys — the exact serialization ChatPreferences uses for chat_list.
        val list = arrayListOf(entry(id = Hash.hash("My chat"), marker = "2"))
        val gson = Gson()
        val json = gson.toJson(list)
        val type: Type = TypeToken.getParameterized(ArrayList::class.java, HashMap::class.java).type
        @Suppress("UNCHECKED_CAST")
        val parsed = gson.fromJson<Any>(json, type) as ArrayList<HashMap<String, String>>
        assertTrue(ChatIdentity.isStable(parsed[0]))
        assertEquals(Hash.hash("My chat"), ChatIdentity.effectiveId(parsed[0]))
        assertEquals(ChatIdentity.HealAction.NONE, ChatIdentity.healAction(parsed[0]))
    }
}
