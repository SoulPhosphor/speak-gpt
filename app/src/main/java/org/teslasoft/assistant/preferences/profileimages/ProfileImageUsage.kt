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

package org.teslasoft.assistant.preferences.profileimages

import android.content.Context
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.memory.MemoryStore

/**
 * Live usage resolution (profile-images-plan.md, USAGE RESOLUTION): the
 * catalog never stores is_used/reference_count, so "is this hash used, and
 * by whom" is always recomputed from the four identity sources - the
 * Default User Image, every Companion, every My Persona, and every
 * user-side Roleplay Character. MemoryStore is read only when
 * [MemoryStore.isProvisioned] is already true; this class never provisions
 * it as a side effect of a usage check.
 *
 * Callers must run [computeAll] off the main thread - it touches
 * SharedPreferences and, when provisioned, the SQLCipher memory database.
 */
object ProfileImageUsage {

    /** One identity that references a hash. [name] is null only for the
     *  Default User Image, which the plan says "should appear by that exact
     *  name" rather than "Default User Image: <something>" - there is only
     *  ever one of it, so it has no separate name to show. */
    data class Reference(val kind: Kind, val name: String?)

    enum class Kind { DEFAULT_USER_IMAGE, COMPANION, MY_PERSONA, ROLEPLAY_CHARACTER }

    /**
     * Every hash referenced by a live identity right now, mapped to the
     * identities referencing it. A hash with zero references is Unused and
     * is simply absent from this map's keys.
     */
    fun computeAll(context: Context): Map<String, List<Reference>> {
        val map = HashMap<String, MutableList<Reference>>()
        fun add(hash: String?, reference: Reference) {
            if (hash.isNullOrEmpty()) return
            map.getOrPut(hash) { ArrayList() }.add(reference)
        }

        val defaultRef = GlobalPreferences.getPreferences(context).getDefaultUserImageRef()
        add(defaultRef, Reference(Kind.DEFAULT_USER_IMAGE, null))

        val personaPreferences = PersonaPreferences.getPersonaPreferences(context)
        for (persona in personaPreferences.getPersonasList()) {
            add(persona.avatarRef, Reference(Kind.COMPANION, persona.label))
        }

        if (MemoryStore.isProvisioned(context)) {
            val store = MemoryStore.getInstance(context)
            for (userPersona in store.getAllUserPersonas()) {
                add(userPersona.imageRef, Reference(Kind.MY_PERSONA, userPersona.name))
            }
            // "Party members and NPC Roleplay Characters are not included" -
            // playedBy == "user" is exactly the user-side subset (the other
            // value is a companion id, for a GM-played character).
            for (roleplayCharacter in store.getAllRoleplayCharacters()) {
                if (roleplayCharacter.playedBy != "user") continue
                add(roleplayCharacter.imageRef, Reference(Kind.ROLEPLAY_CHARACTER, roleplayCharacter.name))
            }
        }

        return map
    }

    /** Whether [hash] has at least one live reference right now. */
    fun isUsed(context: Context, hash: String): Boolean = computeAll(context).containsKey(hash)
}
