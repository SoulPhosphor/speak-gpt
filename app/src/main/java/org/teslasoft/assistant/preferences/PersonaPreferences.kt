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

package org.teslasoft.assistant.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.teslasoft.assistant.preferences.dto.PersonaObject
import org.teslasoft.assistant.preferences.memory.MemoryCompanionSync
import org.teslasoft.assistant.util.StableId

class PersonaPreferences private constructor(private var preferences: SharedPreferences, private val sync: CompanionSync) {
    companion object {
        private var personaPreferences: PersonaPreferences? = null

        fun getPersonaPreferences(context: Context): PersonaPreferences {
            if (personaPreferences == null) {
                personaPreferences = PersonaPreferences(
                    context.getSharedPreferences("personas", Context.MODE_PRIVATE),
                    DefaultCompanionSync(context.applicationContext)
                )
            }

            return personaPreferences!!
        }

        /** Test seam: inject an in-memory SharedPreferences and a recording sync. */
        internal fun createForTest(preferences: SharedPreferences, sync: CompanionSync): PersonaPreferences =
            PersonaPreferences(preferences, sync)
    }

    /**
     * The one-way app-persona -> memory-store companion bridge, behind an
     * interface so persona identity/storage can be unit-tested without the
     * SQLCipher store. The real bridge is best-effort (a store hiccup never
     * blocks a persona save) and no-ops until the store is provisioned.
     */
    interface CompanionSync {
        fun onPersonaSaved(persona: PersonaObject)
        fun onPersonaDeleted(personaId: String)
    }

    private class DefaultCompanionSync(private val appContext: Context) : CompanionSync {
        override fun onPersonaSaved(persona: PersonaObject) =
            MemoryCompanionSync.onPersonaSaved(appContext, persona)
        override fun onPersonaDeleted(personaId: String) =
            MemoryCompanionSync.onPersonaDeleted(appContext, personaId)
    }

    private var listeners: ArrayList<OnPersonaChangeListener> = ArrayList()

    fun addOnPersonaChangeListener(listener: OnPersonaChangeListener) {
        listeners.add(listener)
    }

    private fun getString(key: String, defValue: String): String {
        return preferences.getString(key, defValue)!!
    }

    private fun putString(key: String, value: String) {
        preferences.edit { putString(key, value) }
    }

    fun getPersona(id: String): PersonaObject {
        val label = getString(id + "_label", "")
        val prompt = getString(id + "_prompt", "")
        val activationPromptId = getString(id + "_activation_prompt_id", "")
        val coreLoreBookId = getString(id + "_core_lorebook_id", "")
        val additionalLoreBookIds = getString(id + "_additional_lorebook_ids", "")
        val autoLoadLastLoreBooks = getString(id + "_autoload_last_lorebooks", "false") == "true"
        val lastUsedLoreBookIds = getString(id + "_last_used_lorebook_ids", "")
        val avatarRef = getString(id + "_avatar_ref", "")
        return PersonaObject(
            label, prompt, activationPromptId,
            coreLoreBookId, additionalLoreBookIds, autoLoadLastLoreBooks, lastUsedLoreBookIds,
            avatarRef, id
        )
    }

    /**
     * Save under the object's stable [PersonaObject.id]. A brand-new companion
     * (blank id) is minted a fresh id ONCE, in place; an existing companion
     * keeps its id, so a rename (same id, new label) UPDATES the record — it no
     * longer deletes the old keys and rewrites under a name-derived key, which
     * is what used to orphan avatars, activation-prompt links, per-chat
     * selections and the memory-store companion.
     */
    fun setPersona(persona: PersonaObject) {
        writePersona(persona)

        for (listener in listeners) {
            listener.onPersonaChange()
        }

        // One-way app -> store sync: keep the linked companion's personality
        // mirror fresh (memory system D6). Best-effort, no-op until the memory
        // store exists; never blocks or fails a persona save. The companion is
        // located by the STABLE persona id, so a rename updates the existing
        // companion instead of creating a second one.
        sync.onPersonaSaved(persona)
    }

    private fun writePersona(persona: PersonaObject) {
        val id = StableId.resolve(persona.id, "p-")
        persona.id = id
        putString(id + "_label", persona.label)
        putString(id + "_prompt", persona.prompt)
        putString(id + "_activation_prompt_id", persona.activationPromptId)
        putString(id + "_core_lorebook_id", persona.coreLoreBookId)
        putString(id + "_additional_lorebook_ids", persona.additionalLoreBookIds)
        putString(id + "_autoload_last_lorebooks", if (persona.autoLoadLastLoreBooks) "true" else "false")
        putString(id + "_last_used_lorebook_ids", persona.lastUsedLoreBookIds)
        putString(id + "_avatar_ref", persona.avatarRef)
    }

    /**
     * Record which additional lorebooks a chat last had checked for this
     * persona, so autoLoadLastLoreBooks can restore them in a new chat.
     * Touches only the bookkeeping key, never the persona's own fields.
     */
    fun setLastUsedLoreBookIds(personaId: String, joinedIds: String) {
        putString(personaId + "_last_used_lorebook_ids", joinedIds)
    }

    /**
     * Commit ONLY the avatar for an existing companion, by its stable [id]
     * (Profile Images immediate-save, July 21 2026). Writes just the avatar_ref
     * key — never the label/prompt/activation/lorebook keys — so picking a
     * picture in the editor persists at once without saving (or disturbing) any
     * unsaved edits to the other fields, and backing out cannot undo the
     * picture. Listeners fire so an open companion list re-renders the new
     * picture; identity is never re-derived from the label. A brand-new
     * companion has no stored keys yet and must NOT be persisted here — its
     * pick stays a draft and is written when the companion is first created.
     */
    fun setPersonaAvatarRef(id: String, avatarRef: String) {
        if (id.isEmpty()) return
        putString(id + "_avatar_ref", avatarRef)
        for (listener in listeners) {
            listener.onPersonaChange()
        }
    }

    /**
     * Drop a deleted lorebook from every persona that references it, so no
     * persona keeps pointing at a book that no longer exists.
     */
    fun removeLoreBookFromAllPersonas(lorebookId: String) {
        if (lorebookId.isEmpty()) return
        for (persona in getPersonasList()) {
            var changed = false
            if (persona.coreLoreBookId == lorebookId) {
                persona.coreLoreBookId = ""
                changed = true
            }
            val additional = persona.additionalLoreBookIdList()
            if (additional.remove(lorebookId)) {
                persona.additionalLoreBookIds = PersonaObject.joinIds(additional)
                changed = true
            }
            val lastUsed = persona.lastUsedLoreBookIdList()
            if (lastUsed.remove(lorebookId)) {
                persona.lastUsedLoreBookIds = PersonaObject.joinIds(lastUsed)
                changed = true
            }
            if (changed) setPersona(persona)
        }
    }

    // Deleting a persona now ALSO deletes its companion memory record and the
    // memories owned solely by it (owner ruling, July 20 2026 — supersedes the
    // old "profile-only delete, memories dangle" behaviour). Memories shared
    // with another companion survive with this link removed. The cascade is
    // best-effort inside MemoryCompanionSync (a store hiccup never blocks the
    // app-side delete) and no-ops when the store isn't provisioned.
    fun deletePersona(id: String) {
        removePersonaKeys(id)

        sync.onPersonaDeleted(id)

        for (listener in listeners) {
            listener.onPersonaChange()
        }
    }

    private fun removePersonaKeys(id: String) {
        preferences.edit { remove(id + "_label") }
        preferences.edit { remove(id + "_prompt") }
        preferences.edit { remove(id + "_activation_prompt_id") }
        preferences.edit { remove(id + "_core_lorebook_id") }
        preferences.edit { remove(id + "_additional_lorebook_ids") }
        preferences.edit { remove(id + "_autoload_last_lorebooks") }
        preferences.edit { remove(id + "_last_used_lorebook_ids") }
        preferences.edit { remove(id + "_avatar_ref") }
    }

    fun getPersonasList(): ArrayList<PersonaObject> {
        val list = ArrayList<PersonaObject>()
        for (key in preferences.all.keys) {
            if (key.endsWith("_label")) {
                val id = key.removeSuffix("_label")
                list.add(getPersona(id))
            }
        }

        // R8 bug fix
        if (list == null) {
            return ArrayList()
        }

        return list
    }

    fun interface OnPersonaChangeListener {
        fun onPersonaChange()
    }
}
