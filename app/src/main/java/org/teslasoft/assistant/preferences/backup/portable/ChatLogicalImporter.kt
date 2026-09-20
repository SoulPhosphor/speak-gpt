/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import com.google.gson.Gson
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.SecurePrefs

/**
 * Seeds an EMPTY installation from a planned `chats.json` conversion.
 *
 * Scope, deliberately narrow (Phase 8.6.4): this seeds a destination that has
 * never held a conversation. It is not a restore engine and it cannot become
 * one — a destination that already holds chats is refused outright, which is
 * exactly what keeps this path outside the Phase 9 replacement boundary. It
 * never calls [org.teslasoft.assistant.preferences.backup.ChatRestoreManager]
 * and never quarantines or replaces an existing chat file.
 *
 * Write order matters and is the durability story. Every chat's history and
 * settings are written first; the chat list is written last, in one commit.
 * The list is what makes chats exist, so a run interrupted anywhere before
 * that final commit leaves the destination still empty and still eligible for
 * a clean retry. There is no half-migrated state to reason about.
 *
 * The report carries structural counts and stable ids only. No message text,
 * no titles, no settings values, no keys.
 */
object ChatLogicalImporter {

    data class ChatOutcome(
        val chatId: String,
        val messagesWritten: Int,
        val settingsWritten: Int
    )

    data class Report(
        val chatsWritten: Int,
        val messagesWritten: Int,
        val settingsWritten: Int,
        val chats: List<ChatOutcome>
    )

    enum class RefusalReason {
        /** The destination already holds conversations. Seeding would mean
         *  replacing them, which this converter must never do. */
        DESTINATION_NOT_EMPTY,

        /** The destination's chat list could not be read authoritatively. An
         *  unreadable list is not evidence of an empty one, so it is never
         *  written over. */
        DESTINATION_UNREADABLE
    }

    enum class FailureStage { SETTINGS, HISTORY, CHAT_LIST }

    sealed class Outcome {
        data class Ok(val report: Report) : Outcome()
        data class Refused(val reason: RefusalReason) : Outcome()

        /** A write did not commit. [chatId] is null when the chat list itself
         *  failed. Nothing is visible in the destination unless the stage is
         *  CHAT_LIST, and even then the caller should reset the target. */
        data class Failed(val stage: FailureStage, val chatId: String?) : Outcome()
    }

    fun seedEmptyInstallation(
        context: Context,
        plan: ChatLogicalImportPlan.Plan
    ): Outcome {
        val app = context.applicationContext
        val gson = Gson()

        synchronized(ChatPreferences.CHAT_LIST_LOCK) {
            val existing = ChatPreferences.getChatPreferences()
                .getChatListResult(app, includeFirstMessage = false)
            if (!ChatStorageHealth.isAuthoritative(existing.state)) {
                return Outcome.Refused(RefusalReason.DESTINATION_UNREADABLE)
            }
            if (existing.chats.isNotEmpty()) {
                return Outcome.Refused(RefusalReason.DESTINATION_NOT_EMPTY)
            }

            val outcomes = ArrayList<ChatOutcome>(plan.chats.size)
            for (chat in plan.chats) {
                if (!writeSettings(app, chat)) {
                    return Outcome.Failed(FailureStage.SETTINGS, chat.chatId)
                }
                val historyCommitted = SecurePrefs.get(app, "chat_${chat.chatId}")
                    .edit()
                    .putString("chat", chat.messagesJson)
                    .commit()
                if (!historyCommitted) {
                    return Outcome.Failed(FailureStage.HISTORY, chat.chatId)
                }
                outcomes.add(
                    ChatOutcome(chat.chatId, chat.messageCount, chat.settings.size)
                )
            }

            // Last, and only once every history and settings file is durable.
            val listCommitted = SecurePrefs.get(app, "chat_list")
                .edit()
                .putString("data", gson.toJson(plan.chats.map { it.listRow }))
                .commit()
            if (!listCommitted) return Outcome.Failed(FailureStage.CHAT_LIST, null)

            return Outcome.Ok(
                Report(
                    chatsWritten = outcomes.size,
                    messagesWritten = outcomes.sumOf { it.messagesWritten },
                    settingsWritten = outcomes.sumOf { it.settingsWritten },
                    chats = outcomes
                )
            )
        }
    }

    private fun writeSettings(
        context: Context,
        chat: ChatLogicalImportPlan.ChatPlan
    ): Boolean {
        val editor = SecurePrefs.get(context, "settings.${chat.chatId}").edit()
        for (entry in chat.settings) {
            when (val value = entry.value) {
                is String -> editor.putString(entry.key, value)
                is Boolean -> editor.putBoolean(entry.key, value)
                is Int -> editor.putInt(entry.key, value)
                is Long -> editor.putLong(entry.key, value)
                is Float -> editor.putFloat(entry.key, value)
                is Set<*> -> editor.putStringSet(
                    entry.key,
                    value.mapTo(LinkedHashSet()) { it.toString() }
                )
                // The planner rejects any type it cannot restore, so an
                // unrecognized value here would mean the two drifted apart.
                else -> return false
            }
        }
        return editor.commit()
    }
}
