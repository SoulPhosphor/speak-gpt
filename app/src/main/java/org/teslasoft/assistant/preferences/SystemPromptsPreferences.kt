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
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.dto.SystemPromptObject
import org.teslasoft.assistant.util.Hash

/**
 * The user's library of saved system prompts.
 *
 * Ordering matters here (unlike the activation/persona stores, which read an
 * unordered SharedPreferences key map): the "use whatever's at the top when
 * nothing is chosen" default only makes sense with a stable order, so the whole
 * list is kept as one ordered JSON array in its own prefs file.
 *
 * The actual text the model receives still lives in the SINGLE global
 * `system_message` key (see [Preferences.getSystemMessage]) — that's what the
 * generation funnel reads, unchanged. This store only holds the library and
 * which entry is chosen; [applyEffectiveToGlobal] mirrors the chosen body into
 * that global key. Because the system message is global, the choice is global
 * too: it carries into new chats automatically, which is the owner's
 * "last chosen is used in a fresh chat" rule.
 */
class SystemPromptsPreferences private constructor(private val preferences: SharedPreferences) {
    companion object {
        private var instance: SystemPromptsPreferences? = null

        fun getSystemPromptsPreferences(context: Context): SystemPromptsPreferences {
            if (instance == null) {
                instance = SystemPromptsPreferences(
                    context.applicationContext.getSharedPreferences("system_prompts", Context.MODE_PRIVATE)
                )
            }

            return instance!!
        }

        private const val KEY_LIST = "list"
        private const val KEY_SELECTED = "selected_id"
    }

    fun getSystemPrompts(): ArrayList<SystemPromptObject> {
        val result = ArrayList<SystemPromptObject>()
        val raw = preferences.getString(KEY_LIST, "") ?: ""
        if (raw.isBlank()) return result

        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(
                    SystemPromptObject(
                        id = obj.optString("id"),
                        title = obj.optString("title"),
                        body = obj.optString("body")
                    )
                )
            }
        } catch (_: Exception) {
            // Never wipe the user's prompts on a parse hiccup — return what we
            // could read (possibly empty) and leave the stored blob untouched.
        }

        return result
    }

    private fun saveList(list: List<SystemPromptObject>) {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("title", item.title)
            obj.put("body", item.body)
            array.put(obj)
        }
        preferences.edit { putString(KEY_LIST, array.toString()) }
    }

    fun getById(id: String): SystemPromptObject? {
        if (id.isEmpty()) return null
        return getSystemPrompts().firstOrNull { it.id == id }
    }

    /** Appends a new prompt and returns its freshly minted id. */
    fun add(title: String, body: String): String {
        val list = getSystemPrompts()
        val id = Hash.hash(title + "|" + System.nanoTime() + "|" + list.size)
        list.add(SystemPromptObject(id, title, body))
        saveList(list)
        return id
    }

    /** Updates title/body in place, keeping the id (and list position) stable. */
    fun update(id: String, title: String, body: String) {
        val list = getSystemPrompts()
        val index = list.indexOfFirst { it.id == id }
        if (index == -1) return
        list[index] = SystemPromptObject(id, title, body)
        saveList(list)
    }

    fun delete(id: String) {
        val list = getSystemPrompts()
        list.removeAll { it.id == id }
        saveList(list)
        if (getSelectedId() == id) {
            preferences.edit { remove(KEY_SELECTED) }
        }
    }

    fun getSelectedId(): String {
        return preferences.getString(KEY_SELECTED, "") ?: ""
    }

    fun setSelectedId(id: String) {
        preferences.edit { putString(KEY_SELECTED, id) }
    }

    /**
     * The prompt that should currently be in force: the explicitly selected one
     * if it still exists, otherwise the top of the list, otherwise null (no
     * prompts at all → nothing is sent).
     */
    fun getEffectivePrompt(): SystemPromptObject? {
        val list = getSystemPrompts()
        if (list.isEmpty()) return null
        val selected = list.firstOrNull { it.id == getSelectedId() }
        return selected ?: list.first()
    }

    /**
     * Mirror the effective prompt's body into the global system message that the
     * generation funnel reads. Called after any change so a fresh chat picks up
     * the last chosen (or top) prompt with no change to the generation path.
     *
     * When the library is empty the global message is cleared so nothing is
     * sent (owner rule). This is only ever called after
     * [migrateExistingSystemMessage] has run, so an empty library here means the
     * user genuinely emptied it — not a pre-library message we'd be wiping.
     */
    fun applyEffectiveToGlobal(preferencesRef: Preferences) {
        val effective = getEffectivePrompt()
        preferencesRef.setSystemMessage(effective?.body ?: "")
    }

    /**
     * One-time rescue for users who already had a global system message before
     * this library existed: if the library is empty but a system message is set,
     * fold it in as the first entry so nothing is lost and the row can name it.
     * The title is taken from the user's own text (first line), never invented.
     */
    fun migrateExistingSystemMessage(preferencesRef: Preferences) {
        if (getSystemPrompts().isNotEmpty()) return
        val existing = preferencesRef.getSystemMessage()
        if (existing.isBlank()) return

        val firstLine = existing.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        val title = when {
            firstLine.isEmpty() -> "System prompt"
            firstLine.length > 40 -> firstLine.substring(0, 40).trim() + "…"
            else -> firstLine
        }
        val id = add(title, existing)
        setSelectedId(id)
    }
}
