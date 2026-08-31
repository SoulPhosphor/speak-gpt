/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.conversation

import android.content.Context
import com.google.gson.Gson
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.SecurePrefs
import java.util.UUID

/**
 * The one new-conversation initialization and first-commit entry point.
 *
 * A provisional conversation owns a stable UUID and its ordinary per-chat
 * settings/history stores, but it is deliberately absent from chat_list until
 * [commitPendingConversation] durably records the first user action.
 */
class NewConversationCoordinator(private val context: Context) {
    data class StartRequest(
        val name: String,
        val endpointId: String = "",
        val model: String = "",
        val avatarType: String = "",
        val avatarId: String = "",
        val assistantName: String = ""
    )

    private val app = context.applicationContext

    fun createDefaultPendingConversation(): PendingConversationState {
        val name = "_autoname_${ChatPreferences.getChatPreferences().getAvailableChatIdForAutoname(app)}"
        return createPendingConversation(StartRequest(name))
    }

    /** Reuse the same hidden blank session after launcher/task process restoration. */
    fun createOrRestoreStartupPendingConversation(): PendingConversationState {
        val session = SecurePrefs.get(app, STARTUP_SESSION_FILE)
        val id = session.getString(STARTUP_SESSION_ID, "").orEmpty()
        val name = session.getString(STARTUP_SESSION_NAME, "").orEmpty()
        if (id.isNotBlank() && name.isNotBlank() && isPending(id)) {
            val saved = ChatPreferences.getChatPreferences()
                .getChatListResult(app, includeFirstMessage = false)
                .chats.any { ChatPreferences.storedChatId(it) == id }
            val history = ChatPreferences.getChatPreferences().getChatByIdResult(app, id)
            if (!saved && ChatStorageHealth.isAuthoritative(history.state) && history.messages.isEmpty()) {
                return PendingConversationState(id, name, readMode(id))
            }
        }
        session.edit().clear().commit()
        return createDefaultPendingConversation().also { pending ->
            check(
                session.edit()
                    .putString(STARTUP_SESSION_ID, pending.id)
                    .putString(STARTUP_SESSION_NAME, pending.name)
                    .commit()
            ) { "Unable to retain startup pending conversation" }
        }
    }

    fun createPendingConversation(request: StartRequest): PendingConversationState {
        val chatId = allocateUniqueId()
        initializeSettings(chatId, request)
        val initialized = SecurePrefs.get(app, "settings.$chatId").edit()
            .putString(ConversationMode.MODE_KEY, ConversationMode.CHAT.storedValue)
            .putInt(ConversationMode.MODE_VERSION_KEY, ConversationMode.SCHEMA_VERSION)
            .putBoolean(ConversationMode.PENDING_KEY, true)
            .commit()
        check(initialized) { "Unable to initialize pending conversation settings" }
        check(
            SecurePrefs.get(app, "chat_$chatId").edit()
                .putString("chat", "[]").commit()
        ) { "Unable to initialize pending conversation history" }
        return PendingConversationState(chatId, request.name, ConversationMode.CHAT)
    }

    fun commitPendingConversation(
        state: PendingConversationState,
        messages: ArrayList<HashMap<String, Any>>
    ): PendingConversationCommitResult {
        val result = ChatPreferences.getChatPreferences().commitPendingConversation(
            app,
            state.id,
            state.name,
            state.mode,
            messages
        )
        if (result.succeeded) clearStartupSession(state.id)
        return result
    }

    fun setPendingMode(chatId: String, mode: ConversationMode): Boolean =
        SecurePrefs.get(app, "settings.$chatId").edit()
            .putString(ConversationMode.MODE_KEY, mode.storedValue)
            .putInt(ConversationMode.MODE_VERSION_KEY, ConversationMode.SCHEMA_VERSION)
            .commit()

    fun readMode(chatId: String): ConversationMode = ConversationMode.fromStored(
        SecurePrefs.get(app, "settings.$chatId")
            .getString(ConversationMode.MODE_KEY, ConversationMode.CHAT.storedValue)
    )

    fun isPending(chatId: String): Boolean =
        SecurePrefs.get(app, "settings.$chatId")
            .getBoolean(ConversationMode.PENDING_KEY, false)

    fun hasCommitJournal(chatId: String): Boolean =
        SecurePrefs.get(app, "pending_conversation_journal").contains(chatId)

    /** Resume first commits that crossed the payload boundary before process death. */
    fun recoverPendingCommits() {
        val journal = SecurePrefs.get(app, "pending_conversation_journal")
        journal.all.forEach { (journalId, encoded) ->
            val raw = encoded as? String ?: return@forEach
            val entry = runCatching {
                Gson().fromJson(raw, PendingJournalEntry::class.java)
            }.getOrNull() ?: return@forEach
            if (entry.id != journalId || entry.id.isBlank() || entry.name.isBlank()) return@forEach
            val history = ChatPreferences.getChatPreferences().getChatByIdResult(app, entry.id)
            if (!ChatStorageHealth.isAuthoritative(history.state) || history.messages.isEmpty()) {
                return@forEach
            }
            commitPendingConversation(
                PendingConversationState(entry.id, entry.name, ConversationMode.fromStored(entry.mode)),
                history.messages
            )
        }
    }

    /** Idempotent cleanup for a blank activity that is deliberately abandoned. */
    fun abandonPendingConversation(chatId: String): Boolean {
        if (!isPending(chatId)) return false
        val stillSaved = ChatPreferences.getChatPreferences()
            .getChatListResult(app, includeFirstMessage = false)
            .chats.any { ChatPreferences.storedChatId(it) == chatId }
        if (stillSaved) return false
        val history = SecurePrefs.get(app, "chat_$chatId").edit().clear().commit()
        val settings = SecurePrefs.get(app, "settings.$chatId").edit().clear().commit()
        val abandoned = history && settings
        if (abandoned) clearStartupSession(chatId)
        return abandoned
    }

    private fun allocateUniqueId(): String {
        val existing = ChatPreferences.getChatPreferences()
            .getChatListResult(app, includeFirstMessage = false)
            .chats.mapTo(HashSet()) { ChatPreferences.storedChatId(it) }
        var id: String
        do id = UUID.randomUUID().toString() while (id in existing)
        return id
    }

    private data class PendingJournalEntry(
        val id: String = "",
        val name: String = "",
        val mode: String = ConversationMode.CHAT.storedValue
    )

    private fun clearStartupSession(chatId: String) {
        val session = SecurePrefs.get(app, STARTUP_SESSION_FILE)
        if (session.getString(STARTUP_SESSION_ID, "") == chatId) {
            session.edit().clear().commit()
        }
    }

    private companion object {
        const val STARTUP_SESSION_FILE = "pending_startup_conversation"
        const val STARTUP_SESSION_ID = "id"
        const val STARTUP_SESSION_NAME = "name"
    }

    /** Exact extraction of the legacy AddChatDialogFragment initialization. */
    private fun initializeSettings(chatId: String, request: StartRequest) {
        val defaults = Preferences.getPreferences(app, "")
        val endpointId = request.endpointId.ifBlank { defaults.getApiEndpointId() }
        val profile = ApiEndpointPreferences.getApiEndpointPreferences(app)
            .getApiEndpoint(app, endpointId)
        val created = Preferences.getPreferences(app, chatId)

        created.setPreferences(chatId, app)
        created.resetNewChatInheritance()
        created.initializeNewChatQuickSettings()
        created.setResolution(defaults.getResolution())
        created.setAudioModel(defaults.getAudioModel())
        created.setModel(request.model.ifBlank { profile.model })
        created.setMaxTokens(profile.maxTokens)
        created.setPrefix(profile.prefix)
        created.setEndSeparator(profile.endSeparator)
        created.setPrompt(defaults.getPrompt())
        created.setSystemMessage(defaults.getSystemMessage())
        created.setAutoLangDetect(defaults.getAutoLangDetect())
        created.setApiEndpointId(endpointId)
        created.setLogitBiasesConfigId(defaults.getLogitBiasesConfigId())
        created.setTemperature(profile.temperature)
        created.setTopP(profile.topP)
        created.setFrequencyPenalty(profile.frequencyPenalty)
        created.setPresencePenalty(profile.presencePenalty)
        created.setStreaming(defaults.getStreaming())
        created.setAvatarType(request.avatarType.ifBlank { defaults.getAvatarType() })
        created.setAvatarId(request.avatarId.ifBlank { defaults.getAvatarId() })
        created.setAssistantName(request.assistantName.ifBlank { defaults.getAssistantName() })
    }
}
