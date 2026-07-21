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

/**
 * The ONE funnel that turns a chat-list entry into the chat's id
 * (chat-id-stable-identity-plan.md — read it before touching this file).
 *
 * A chat's id used to be recomputed as the SHA-256 hash of its display name
 * at dozens of read sites while the list entry ALSO stored an `id` field;
 * the two only agreed
 * because every rename rewrote both. A build where some code trusts the
 * stored id and other code recomputes the hash splits a chat's identity the
 * first time they disagree (history under one id, transcripts under
 * another). Every reader goes through [effectiveId] so that disagreement is
 * structurally impossible; a CI source-scan test (ChatIdHashGuardTest) pins
 * the remaining name-hash call sites so no new recompute site can appear.
 *
 * Authority rules (plan §6):
 *  - An entry marked [KEY_IDENTITY_VERSION] = "2" keeps its stored id
 *    verbatim, forever. The marker is the only durable authority — it is
 *    never inferred from the stored id equalling the name hash, because
 *    after the first stable-id rename that equality is legitimately false.
 *  - An entry whose id starts with [STABLE_ID_PREFIX] is trusted even
 *    without the marker: a "c-" id can only have been minted by the new
 *    code, and falling back to a meaningless name-hash would present an
 *    empty chat if the marker were ever absent before healing runs.
 *  - Anything else resolves to the hash of the name — byte-identical legacy
 *    behavior.
 */
object ChatIdentity {

    /** Chat-list entry key marking the stored id as the permanent identity. */
    const val KEY_IDENTITY_VERSION = "identity_version"

    /** The only marker value ever written. */
    const val IDENTITY_VERSION_STABLE = "2"

    /** Prefix of ids minted for new chats (StableId.newId("c-")). A hex
     *  SHA-256 legacy id can never start with this, so the two id families
     *  can never collide. */
    const val STABLE_ID_PREFIX = "c-"

    /**
     * True when this entry's stored id is its permanent identity — the
     * marker says so, or the id itself is a minted "c-" id. Freeze
     * semantics (name-only rename, never re-derive) act exactly on these
     * entries. A marked entry with a blank id is NOT stable: the resolver
     * would have nothing to return, so it stays on the legacy fallback
     * until healing repairs the id.
     */
    fun isStable(entry: Map<String, String>): Boolean {
        val storedId = entry["id"] ?: ""
        if (storedId.isBlank()) return false
        return entry[KEY_IDENTITY_VERSION] == IDENTITY_VERSION_STABLE ||
            storedId.startsWith(STABLE_ID_PREFIX)
    }

    /**
     * The chat's id. Stable entries return their stored id; everything else
     * returns the name hash exactly as the legacy read sites computed it
     * (`entry["name"].toString()` — including the "null" string for an
     * absent name, so the fallback is byte-identical to the code it
     * replaced).
     */
    fun effectiveId(entry: Map<String, String>): String {
        if (isStable(entry)) return entry["id"] ?: ""
        return Hash.hash(entry["name"].toString())
    }

    /** What the startup healing pass (plan §7) does to one unhealed entry. */
    enum class HealAction {
        /** Already stable — skip instantly; this is what makes the
         *  every-start pass cheap and idempotent. */
        NONE,
        /** The stored id is verified (matches the name hash, or is a minted
         *  "c-" id): add the marker, touch nothing else. */
        ADD_MARKER,
        /** No stored id at all: fill it with the entry's effective id (the
         *  name hash — the id every reader already resolves), then mark. */
        SET_ID_AND_MARK,
        /** Stored id disagrees with the name hash and is not a minted id.
         *  §7 rule 4: NO repair of any kind — no file probing, no history
         *  switch, no re-point. The entry stays unmarked (the resolver keeps
         *  it on the name hash, so the user keeps seeing exactly what they
         *  see today) and is reported for manual inspection. */
        MISMATCH_NO_REPAIR
    }

    /**
     * Pure §7 decision for a single entry. The blank-id check runs first so
     * a marked-but-idless entry (which [isStable] rejects) is repaired
     * rather than skipped.
     */
    fun healAction(entry: Map<String, String>): HealAction {
        val storedId = entry["id"] ?: ""
        if (storedId.isBlank()) return HealAction.SET_ID_AND_MARK
        if (entry[KEY_IDENTITY_VERSION] == IDENTITY_VERSION_STABLE) return HealAction.NONE
        if (storedId.startsWith(STABLE_ID_PREFIX)) return HealAction.ADD_MARKER
        // The name-hash comparison rides on effectiveId: for an unmarked,
        // non-minted entry it IS the name hash, so no second hash site.
        return if (storedId == effectiveId(entry)) HealAction.ADD_MARKER
        else HealAction.MISMATCH_NO_REPAIR
    }

    /**
     * The healing plan for a whole chat-list view, position-aligned with
     * [entries]. An EMPTY plan when the list view is not authoritative
     * (locked/corrupt storage masks the real list — plan §7: skip entirely,
     * retry next start; a masked view must never mint permanent identity).
     */
    fun planHealing(entries: List<Map<String, String>>, authoritative: Boolean): List<HealAction> {
        if (!authoritative) return emptyList()
        return entries.map { healAction(it) }
    }

    /** Why a rename is allowed or refused (plan commit 3). */
    enum class RenameDecision {
        /** Stable entry, new name free: a name-only list update. */
        OK,
        /** No entry carries the old name — nothing to rename. */
        NOT_FOUND,
        /** §7 rule 4 freeze: the entry's stored id was never verified
         *  (unmarked and not a minted id), so a rename — which would have
         *  to either move files under a new hash or silently adopt the
         *  unverified id — is refused until the entry is healed or
         *  manually inspected. */
        NOT_STABLE,
        /** Another chat already carries the new name. Duplicate names stay
         *  disallowed (relaxing that is an owner decision, not a side
         *  effect of stable ids). */
        DUPLICATE_NAME
    }

    /** Pure rename gate — the id never changes on any outcome. */
    fun renameDecision(
        entries: List<Map<String, String>>,
        previousName: String,
        newName: String
    ): RenameDecision {
        val entry = entries.firstOrNull { it["name"] == previousName }
            ?: return RenameDecision.NOT_FOUND
        if (!isStable(entry)) return RenameDecision.NOT_STABLE
        if (entries.any { it !== entry && it["name"] == newName }) {
            return RenameDecision.DUPLICATE_NAME
        }
        return RenameDecision.OK
    }

    /**
     * A brand-new chat-list entry: minted stable id, marker included from
     * birth. Creation is the only place an entry is built, so the marker can
     * never be forgotten on a new chat.
     */
    fun newEntry(name: String, id: String, timestampMillis: Long): HashMap<String, String> {
        val map = HashMap<String, String>()
        map["name"] = name
        map["id"] = id
        map["timestamp"] = timestampMillis.toString()
        map["pinned"] = "false"
        map[KEY_IDENTITY_VERSION] = IDENTITY_VERSION_STABLE
        return map
    }
}
