/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.conversation

import android.content.Context
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.ChatPreferences
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
    ): PendingConversationCommitResult =
        ChatPreferences.getChatPreferences().commitPendingConversation(
            app,
            state.id,
            state.name,
            state.mode,
            messages
        )

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

    /** Idempotent cleanup for a blank activity that is deliberately abandoned. */
    fun abandonPendingConversation(chatId: String): Boolean {
        if (!isPending(chatId)) return false
        val stillSaved = ChatPreferences.getChatPreferences()
            .getChatListResult(app, includeFirstMessage = false)
            .chats.any { ChatPreferences.storedChatId(it) == chatId }
        if (stillSaved) return false
        val history = SecurePrefs.get(app, "chat_$chatId").edit().clear().commit()
        val settings = SecurePrefs.get(app, "settings.$chatId").edit().clear().commit()
        return history && settings
    }

    private fun allocateUniqueId(): String {
        val existing = ChatPreferences.getChatPreferences()
            .getChatListResult(app, includeFirstMessage = false)
            .chats.mapTo(HashSet()) { ChatPreferences.storedChatId(it) }
        var id: String
        do id = UUID.randomUUID().toString() while (id in existing)
        return id
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
