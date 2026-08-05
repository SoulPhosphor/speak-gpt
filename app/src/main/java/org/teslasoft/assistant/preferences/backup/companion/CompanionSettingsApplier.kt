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

package org.teslasoft.assistant.preferences.backup.companion

import android.content.Context
import android.content.SharedPreferences
import org.teslasoft.assistant.preferences.Preferences

/** A staged settings write did not reach disk (editor.commit() == false). */
class CompanionSettingsWriteException : RuntimeException("app settings could not be written")

/**
 * Captures and applies the app-settings side of a companion/roleplay restore
 * (companion-roleplay-backup-plan.md §6.3 step 3). Whole-file replacement of
 * the three preference stores plus the mirrored global system message, with
 * synchronous commits, so both directions — apply the backup's values, or
 * write the captured snapshot back — are idempotent: startup recovery can
 * repeat either one safely after a crash.
 */
object CompanionSettingsApplier {

    private const val PREFS_PERSONAS = "personas"
    private const val PREFS_ACTIVATION = "activation_prompts"
    private const val PREFS_SYSTEM_PROMPTS = "system_prompts"

    /** The current values of every key about to change. */
    fun snapshot(context: Context): CompanionSettingsPayload {
        val appContext = context.applicationContext
        return CompanionSettingsPayload(
            personas = HashMap(prefs(appContext, PREFS_PERSONAS).all),
            activationPrompts = HashMap(prefs(appContext, PREFS_ACTIVATION).all),
            systemPrompts = HashMap(prefs(appContext, PREFS_SYSTEM_PROMPTS).all),
            systemMessage = Preferences.getPreferences(appContext, "").getSystemMessage()
        )
    }

    /**
     * Replaces the three preference files with [payload]'s maps and mirrors
     * [CompanionSettingsPayload.systemMessage] into the global system message.
     * Throws [CompanionSettingsWriteException] when a commit fails, so the
     * caller's transaction/rollback path takes over.
     */
    fun apply(context: Context, payload: CompanionSettingsPayload) {
        val appContext = context.applicationContext
        replaceAll(prefs(appContext, PREFS_PERSONAS), payload.personas)
        replaceAll(prefs(appContext, PREFS_ACTIVATION), payload.activationPrompts)
        replaceAll(prefs(appContext, PREFS_SYSTEM_PROMPTS), payload.systemPrompts)
        Preferences.getPreferences(appContext, "").setSystemMessage(payload.systemMessage)
    }

    private fun replaceAll(prefs: SharedPreferences, values: Map<String, Any?>) {
        val editor = prefs.edit().clear()
        for ((key, value) in values) {
            when (value) {
                null -> { /* absent key */ }
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> {
                    val set = LinkedHashSet<String>()
                    for (item in value) set.add(item.toString())
                    editor.putStringSet(key, set)
                }
                else -> throw CompanionSettingsWriteException()
            }
        }
        if (!editor.commit()) throw CompanionSettingsWriteException()
    }

    private fun prefs(context: Context, name: String): SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)
}
