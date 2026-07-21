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

import java.util.UUID

/**
 * Stable random identifiers for records whose identity must NOT be derived
 * from a mutable display name.
 *
 * Companions, activation prompts, API endpoints and logit-bias configs used to
 * key themselves by `Hash.hash(label)`, so renaming an item changed its id and
 * orphaned every reference to it (chats, memories, avatars, favorites, per-chat
 * selections). Their id now comes from here — minted ONCE at creation and never
 * recomputed from the name. A UUID (mirrors `MemoryStore.newId`, the repo's
 * existing stable-id helper, without pulling in the SQLCipher store) so a
 * generated id can never collide with a legacy SHA-256 hash id.
 *
 * Legacy records keep their original hashed id: it is already stable per record
 * (the label is fixed once stored), so the preference-key IS treated as the
 * permanent id and is never regenerated — see [resolve].
 */
object StableId {
    /** A fresh, globally-unique id with a short type [prefix] (e.g. "p-", "ep-"). */
    fun newId(prefix: String): String = prefix + UUID.randomUUID().toString()

    /**
     * The id to persist a record under: its [existingId] when it already has one
     * (legacy hashed id or a previously-minted stable id — kept verbatim), or a
     * freshly minted id when the record is brand new. Never derives an id from a
     * name, and never replaces an id a record already carries.
     */
    fun resolve(existingId: String, prefix: String): String =
        existingId.ifBlank { newId(prefix) }
}
