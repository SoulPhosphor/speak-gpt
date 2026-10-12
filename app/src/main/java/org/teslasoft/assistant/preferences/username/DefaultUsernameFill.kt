/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.username

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.ui.chat.ChatSpeakerNames

/**
 * Apply to Previous Chats with No Name (owner ruling, Oct 12 2026). While the
 * setting is on, every user message saved without a name (it displays the
 * built-in "User") gets the Default Displayed Username written into the
 * saved chat. Messages that already carry any name are never touched, so a
 * later change of the default name only reaches messages that are still
 * nameless.
 *
 * Runs as a WorkManager job so it finishes quietly in the background even if
 * the app is closed, and is safe to repeat. A chat that is open on screen is
 * skipped here — the open chat fills its own in-memory messages and saves
 * them through its normal path, so the two can never overwrite each other.
 */
object DefaultUsernameFill {

    private const val WORK_NAME = "default_username_fill"

    // Chats currently open in ChatActivity (open count per id). Guarded by
    // ChatPreferences.CHAT_LIST_LOCK so opening a chat waits for an
    // in-progress fill of that chat to finish before reading it.
    private val openChats = HashMap<String, Int>()

    /** Call on a background thread BEFORE reading the chat's history. */
    fun markOpen(chatId: String) {
        synchronized(ChatPreferences.CHAT_LIST_LOCK) {
            openChats[chatId] = (openChats[chatId] ?: 0) + 1
        }
    }

    fun markClosed(chatId: String) {
        synchronized(ChatPreferences.CHAT_LIST_LOCK) {
            val count = (openChats[chatId] ?: 0) - 1
            if (count > 0) openChats[chatId] = count else openChats.remove(chatId)
        }
    }

    /** The name to fill with, or null when the setting is off or no default
     *  name is set. */
    fun activeName(context: Context): String? {
        val preferences = Preferences.getPreferences(context, "")
        if (!preferences.getApplyDefaultUsernameToPreviousChats()) return null
        return preferences.getDefaultDisplayedUsername().trim().takeIf { it.isNotEmpty() }
    }

    /** Queue the background fill (replacing any queued run). */
    fun schedule(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DefaultUsernameFillWorker>().build()
        )
    }

    /**
     * Writes [name] into every nameless user message of [messages]. Returns
     * true when anything changed. Pure; unit-tested.
     */
    fun fillNameless(messages: List<MutableMap<String, Any>>, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        var changed = false
        for (message in messages) {
            val isBot = message["isBot"] == true || message["isBot"]?.toString() == "true"
            if (isBot) continue
            if (message[ChatSpeakerNames.USER_NAME_KEY]?.toString()?.isNotBlank() == true) continue
            message[ChatSpeakerNames.USER_NAME_KEY] = trimmed
            changed = true
        }
        return changed
    }

    /**
     * Fills every closed chat. Returns false when some chat could not be read
     * or saved (it is left untouched), so the job can retry later.
     */
    fun runAll(context: Context): Boolean {
        val name = activeName(context) ?: return true
        val chatPreferences = ChatPreferences.getChatPreferences()
        val listed = chatPreferences.getChatListResult(context, includeFirstMessage = false)
        if (!ChatStorageHealth.isAuthoritative(listed.state)) return false
        var complete = true
        for (chatId in listed.chats.map { ChatPreferences.storedChatId(it) }) {
            synchronized(ChatPreferences.CHAT_LIST_LOCK) {
                if (activeName(context) != name) return true // superseded by a newer run
                if (openChats.containsKey(chatId)) return@synchronized
                // Re-check under the lock: a chat deleted meanwhile must not
                // be recreated by this write.
                val current = chatPreferences.getChatListResult(context, includeFirstMessage = false)
                if (!ChatStorageHealth.isAuthoritative(current.state)) {
                    complete = false
                    return@synchronized
                }
                if (current.chats.none { ChatPreferences.storedChatId(it) == chatId }) return@synchronized
                val history = chatPreferences.getChatByIdResult(context, chatId)
                if (!ChatStorageHealth.isAuthoritative(history.state)) {
                    complete = false
                    return@synchronized
                }
                if (fillNameless(history.messages, name) &&
                    chatPreferences.saveChatHistory(context, chatId, history.messages, synchronous = true) !=
                    ChatStorageHealth.WriteOutcome.OK
                ) {
                    complete = false
                }
            }
        }
        return complete
    }
}
