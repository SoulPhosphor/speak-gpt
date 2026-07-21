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
import org.teslasoft.assistant.util.StableId

class ActivationPromptPreferences private constructor(private var preferences: SharedPreferences) {
    companion object {
        private var activationPromptPreferences: ActivationPromptPreferences? = null

        fun getActivationPromptPreferences(context: Context): ActivationPromptPreferences {
            if (activationPromptPreferences == null) {
                activationPromptPreferences = ActivationPromptPreferences(context.getSharedPreferences("activation_prompts", Context.MODE_PRIVATE))
            }

            return activationPromptPreferences!!
        }

        /** Test seam: build against an injected (in-memory) SharedPreferences. */
        internal fun createForTest(preferences: SharedPreferences): ActivationPromptPreferences =
            ActivationPromptPreferences(preferences)
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
        return ActivationPromptObject(label, prompt, id)
    }

    /**
     * Save under the object's stable [ActivationPromptObject.id]. A brand-new
     * object (blank id) is minted a fresh id ONCE, in place, so the caller can
     * read it back; an existing prompt keeps its id, so a rename (same id, new
     * label) updates the record instead of creating a second one under a new
     * name-derived key.
     */
    fun setActivationPrompt(activationPrompt: ActivationPromptObject) {
        val id = StableId.resolve(activationPrompt.id, "ap-")
        activationPrompt.id = id
        putString(id + "_label", activationPrompt.label)
        putString(id + "_prompt", activationPrompt.prompt)
    }

    fun deleteActivationPrompt(id: String) {
        preferences.edit { remove(id + "_label") }
        preferences.edit { remove(id + "_prompt") }
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
