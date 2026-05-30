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
import org.teslasoft.assistant.util.Hash

class PersonaPreferences private constructor(private var preferences: SharedPreferences) {
    companion object {
        private var personaPreferences: PersonaPreferences? = null

        fun getPersonaPreferences(context: Context): PersonaPreferences {
            if (personaPreferences == null) {
                personaPreferences = PersonaPreferences(context.getSharedPreferences("personas", Context.MODE_PRIVATE))
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
        return PersonaObject(label, prompt)
    }

    fun setPersona(persona: PersonaObject) {
        val id = Hash.hash(persona.label)
        putString(id + "_label", persona.label)
        putString(id + "_prompt", persona.prompt)

        for (listener in listeners) {
            listener.onPersonaChange()
        }
    }

    fun deletePersona(id: String) {
        preferences.edit { remove(id + "_label") }
        preferences.edit { remove(id + "_prompt") }

        for (listener in listeners) {
            listener.onPersonaChange()
        }
    }

    fun editPersona(oldLabel: String, persona: PersonaObject) {
        deletePersona(Hash.hash(oldLabel))
        setPersona(persona)
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
