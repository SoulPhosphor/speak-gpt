/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

import android.content.Context

/** The cutting settings indexing uses (memory development plan G.5b). */
object ConversationIndexSettings {
    /** G.5b cutter rule 3's starting value, measured with the model's own counter. */
    const val TARGET_TOKENS = 256

    /**
     * Words carried from the passage before into a passage's search text
     * (G.5b cutter rule 5). **Owner (2026-11-24):** 20, the smaller of the two
     * values discussed, to be tested first; fixed, not a user setting.
     */
    const val OVERLAP_WORDS = 20
}

/**
 * The one-line hookups the chat flows call (G.12). Each returns at once:
 * when Index Conversations is off it does nothing, and otherwise it only
 * records the change and hands the work to the index's background thread.
 * Nothing here can throw into, slow down or change the calling chat
 * operation (G.5a rule 10).
 *
 * A deleted or renamed chat is also followed while the setting is off but an
 * index exists, because turning indexing off keeps the index (Owner,
 * 2026-11-24) and nothing derived from a deleted chat may stay in it.
 */
object ConversationIndexHooks {

    /** The chat's history was saved: new messages, a finished reply, edits, deletions, hiding or showing, another version. */
    fun chatSaved(context: Context, chatId: String) = hand(context) {
        ConversationIndexChatChanges.changed(chatId)
        it.chatChanged(chatId)
    }

    fun chatsDeleted(context: Context, chatIds: Collection<String>) = hand(context, whileIndexExists = true) {
        chatIds.forEach(ConversationIndexChatChanges::changed)
        it.chatsDeleted(chatIds.toList())
    }

    /** A rename moved the chat to a new ID (the older-chat rename path). */
    fun chatRenamed(context: Context, oldChatId: String, newChatId: String) = hand(context, whileIndexExists = true) {
        ConversationIndexChatChanges.changed(oldChatId)
        ConversationIndexChatChanges.changed(newChatId)
        it.chatMoved(oldChatId, newChatId)
    }

    /** A backup was restored or merged into the chats. */
    fun chatSetReplaced(context: Context) = hand(context) { it.chatSetReplaced() }

    fun embeddingModelChanged(context: Context) = hand(context) { it.embeddingModelChanged() }

    fun appStarted(context: Context) {
        if (!ConversationIndexSetting.isOn(context)) return
        try {
            ConversationIndexRuntime.appStarted(context)
        } catch (_: Exception) {
            // The index is derived; a failure here never reaches the chat.
        }
    }

    private inline fun hand(
        context: Context,
        whileIndexExists: Boolean = false,
        block: (ConversationIndexCoordinator) -> Unit
    ) {
        try {
            if (!ConversationIndexSetting.isOn(context) &&
                !(whileIndexExists && ConversationIndexStore.exists(context))
            ) return
            block(ConversationIndexRuntime.coordinator(context))
        } catch (_: Exception) {
            // The index is derived; a failure here never reaches the chat.
        }
    }
}
