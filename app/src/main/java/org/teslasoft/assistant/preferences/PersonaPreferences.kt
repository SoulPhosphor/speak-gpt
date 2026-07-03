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
import org.teslasoft.assistant.util.Hash

class PersonaPreferences private constructor(private var preferences: SharedPreferences, private val appContext: Context) {
    companion object {
        private var personaPreferences: PersonaPreferences? = null

        fun getPersonaPreferences(context: Context): PersonaPreferences {
            if (personaPreferences == null) {
                personaPreferences = PersonaPreferences(
                    context.getSharedPreferences("personas", Context.MODE_PRIVATE),
                    context.applicationContext
                )
            }

            return personaPreferences!!
        }
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
        return PersonaObject(
            label, prompt, activationPromptId,
            coreLoreBookId, additionalLoreBookIds, autoLoadLastLoreBooks, lastUsedLoreBookIds
        )
    }

    fun setPersona(persona: PersonaObject) {
        writePersona(persona)

        for (listener in listeners) {
            listener.onPersonaChange()
        }

        // One-way app -> store sync: keep the linked companion's personality
        // mirror fresh (memory system D6). Best-effort, no-op until the memory
        // store exists; never blocks or fails a persona save.
        MemoryCompanionSync.onPersonaSaved(appContext, null, persona)
    }

    private fun writePersona(persona: PersonaObject) {
        val id = Hash.hash(persona.label)
        putString(id + "_label", persona.label)
        putString(id + "_prompt", persona.prompt)
        putString(id + "_activation_prompt_id", persona.activationPromptId)
        putString(id + "_core_lorebook_id", persona.coreLoreBookId)
        putString(id + "_additional_lorebook_ids", persona.additionalLoreBookIds)
        putString(id + "_autoload_last_lorebooks", if (persona.autoLoadLastLoreBooks) "true" else "false")
        putString(id + "_last_used_lorebook_ids", persona.lastUsedLoreBookIds)
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

    // Deleting a persona deliberately does NOT touch the memory store: the
    // store owns continuity, and deleting companions is a user-only action in
    // the memory editor. The companion's app link simply dangles until the
    // user decides there.
    fun deletePersona(id: String) {
        removePersonaKeys(id)

        for (listener in listeners) {
            listener.onPersonaChange()
        }
    }

    fun editPersona(oldLabel: String, persona: PersonaObject) {
        val oldId = Hash.hash(oldLabel)
        removePersonaKeys(oldId)
        writePersona(persona)

        for (listener in listeners) {
            listener.onPersonaChange()
        }

        // Renames change the persona id (edit = delete + recreate), so the
        // companion link must be re-pointed under the OLD id — that's the whole
        // reason the sync hook exists.
        MemoryCompanionSync.onPersonaSaved(appContext, oldId, persona)
    }

    private fun removePersonaKeys(id: String) {
        preferences.edit { remove(id + "_label") }
        preferences.edit { remove(id + "_prompt") }
        preferences.edit { remove(id + "_activation_prompt_id") }
        preferences.edit { remove(id + "_core_lorebook_id") }
        preferences.edit { remove(id + "_additional_lorebook_ids") }
        preferences.edit { remove(id + "_autoload_last_lorebooks") }
        preferences.edit { remove(id + "_last_used_lorebook_ids") }
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
