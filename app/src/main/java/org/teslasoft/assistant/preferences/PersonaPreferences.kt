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
import kotlinx.serialization.json.Json
import org.teslasoft.assistant.preferences.dto.PersonaObject
import java.util.UUID

class PersonaPreferences private constructor(private var preferences: SharedPreferences) {
    companion object {
        private const val KEY_PERSONAS = "personas_list"
        private const val KEY_ACTIVE = "active_persona_id"

        private var instance: PersonaPreferences? = null

        fun getInstance(context: Context): PersonaPreferences {
            if (instance == null) {
                instance = PersonaPreferences(context.getSharedPreferences("personas", Context.MODE_PRIVATE))
            }
            return instance!!
        }

        /**
         * Returns the active persona's system prompt if one is selected, otherwise the
         * caller's fallback (typically the legacy per-chat system_message). Blank
         * persona prompts fall through to the fallback so an unconfigured persona
         * cannot silently wipe the system message.
         */
        fun resolveSystemMessage(context: Context, fallback: String): String {
            val active = getInstance(context).getActivePersona()
            return if (active != null && active.systemPrompt.isNotBlank()) active.systemPrompt else fallback
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun getPersonas(): ArrayList<PersonaObject> {
        val raw = preferences.getString(KEY_PERSONAS, null) ?: return ArrayList()
        return try {
            ArrayList(json.decodeFromString<List<PersonaObject>>(raw))
        } catch (_: Exception) {
            ArrayList()
        }
    }

    fun setPersonas(personas: List<PersonaObject>) {
        preferences.edit { putString(KEY_PERSONAS, json.encodeToString<List<PersonaObject>>(personas)) }
    }

    fun getActivePersonaId(): String {
        return preferences.getString(KEY_ACTIVE, "") ?: ""
    }

    fun setActivePersonaId(id: String) {
        preferences.edit { putString(KEY_ACTIVE, id) }
    }

    fun getActivePersona(): PersonaObject? {
        val id = getActivePersonaId()
        if (id.isEmpty()) return null
        return getPersonas().firstOrNull { it.id == id }
    }

    fun addPersona(name: String, systemPrompt: String): PersonaObject {
        val persona = PersonaObject(UUID.randomUUID().toString(), name, systemPrompt)
        val list = getPersonas()
        list.add(persona)
        setPersonas(list)
        return persona
    }

    fun updatePersona(id: String, name: String, systemPrompt: String) {
        val list = getPersonas()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        list[index] = list[index].copy(name = name, systemPrompt = systemPrompt)
        setPersonas(list)
    }

    fun deletePersona(id: String) {
        val list = getPersonas()
        list.removeAll { it.id == id }
        setPersonas(list)
        if (getActivePersonaId() == id) setActivePersonaId("")
    }
}
