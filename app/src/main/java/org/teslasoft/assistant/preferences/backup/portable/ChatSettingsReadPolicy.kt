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

package org.teslasoft.assistant.preferences.backup.portable

/**
 * Pure decision (unit-tested) for reading one chat's per-chat settings file
 * into a portable backup (owner correction, July 22 2026).
 *
 * The trap this exists to close: SecurePrefs returns an INERT view for a
 * LOCKED file — reads present an EMPTY map without throwing — so a naive
 * ".all with a catch" serializer would emit `complete: true` while silently
 * omitting that chat's settings, the exact Round-4 masquerade (locked data
 * presenting as empty data) this codebase forbids. Therefore:
 *
 *  - locked file            -> UNAVAILABLE (fails the whole chats artifact)
 *  - read threw             -> UNAVAILABLE (never substitute an empty map)
 *  - readable, has values   -> READABLE
 *  - readable, genuinely
 *    empty                  -> READABLE (an empty map from an UNLOCKED file
 *                              is honest data — a chat may simply have no
 *                              per-chat settings yet)
 *
 * The lock check must be made via SecurePrefs.isLockedName AFTER the open
 * attempt, because classification happens on open.
 */
object ChatSettingsReadPolicy {

    sealed class Decision {
        data class Readable(val entries: Map<String, Any?>) : Decision()
        object Unavailable : Decision()
    }

    /**
     * @param locked SecurePrefs.isLockedName(name), checked after the open.
     * @param entriesOrNull the .all map, or null when reading it threw.
     */
    fun decide(locked: Boolean, entriesOrNull: Map<String, Any?>?): Decision = when {
        locked -> Decision.Unavailable
        entriesOrNull == null -> Decision.Unavailable
        else -> Decision.Readable(entriesOrNull)
    }
}
