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
import org.teslasoft.assistant.preferences.dto.ActivationPromptObject
import org.teslasoft.assistant.util.Hash

class ActivationPromptPreferences private constructor(private var preferences: SharedPreferences) {
    companion object {
        private var activationPromptPreferences: ActivationPromptPreferences? = null

        fun getActivationPromptPreferences(context: Context): ActivationPromptPreferences {
            if (activationPromptPreferences == null) {
                activationPromptPreferences = ActivationPromptPreferences(context.getSharedPreferences("activation_prompts", Context.MODE_PRIVATE))
            }

            return activationPromptPreferences!!
        }
    }

    private fun getString(key: String, defValue: String): String {
        return preferences.getString(key, defValue)!!
    }

    private fun putString(key: String, value: String) {
        preferences.edit { putString(key, value) }
    }

    fun getActivationPrompt(id: String): ActivationPromptObject {
        val label = getString(id + "_label", "")
        val prompt = getString(id + "_prompt", "")
        return ActivationPromptObject(label, prompt)
    }

    fun setActivationPrompt(activationPrompt: ActivationPromptObject) {
        val id = Hash.hash(activationPrompt.label)
        putString(id + "_label", activationPrompt.label)
        putString(id + "_prompt", activationPrompt.prompt)
    }

    fun deleteActivationPrompt(id: String) {
        preferences.edit { remove(id + "_label") }
        preferences.edit { remove(id + "_prompt") }
    }

    fun editActivationPrompt(oldLabel: String, activationPrompt: ActivationPromptObject) {
        deleteActivationPrompt(Hash.hash(oldLabel))
        setActivationPrompt(activationPrompt)
    }

    fun getActivationPromptsList(): ArrayList<ActivationPromptObject> {
        val list = ArrayList<ActivationPromptObject>()
        for (key in preferences.all.keys) {
            if (key.endsWith("_label")) {
                val id = key.removeSuffix("_label")
                list.add(getActivationPrompt(id))
            }
        }

        // R8 bug fix
        if (list == null) {
            return ArrayList()
        }

        return list
    }
}
