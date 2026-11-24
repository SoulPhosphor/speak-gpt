/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.teslasoft.assistant.preferences.ChatHistoryWriteOrder
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatSetSourceGeneration
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.damaged.DamagedChatOperation
import org.teslasoft.assistant.ui.chat.ModelFacingMessageText

/**
 * Where the conversation index reads the stored chats (memory development
 * plan G.5a rules 7 and 9). It reads through the app's chat-reading layer
 * only and never writes to a chat.
 */
interface IndexChatSource : IndexMessageSource {

    sealed interface Read {
        /** Every stored message, in stored order, as read at [version]. */
        data class Messages(val version: String, val messages: List<IndexSourceMessage>) : Read

        /** A message has no permanent ID yet: the chat waits until it is opened (G.5a rule 7). */
        data object WithoutPermanentIds : Read

        /** Locked or damaged storage, or a save still in progress; tried again later. */
        data object Unavailable : Read
    }

    fun read(chatId: String): Read

    /** The chat's version now: cheap, and different after every change to the stored chat. */
    fun chatVersion(chatId: String): String

    /** Every chat in the chat list, newest first; null when the list cannot be read. */
    fun chatIdsNewestFirst(): List<String>?
}

/**
 * The conversion from stored messages, shared by the reader and its tests.
 * The model-facing text is the Summarizer snapshot's text, built by the same
 * functions the chat screen uses for its requests ([ModelFacingMessageText]).
 */
object IndexSourceMessages {

    /** The chat's messages for the index, or null when one has no permanent ID yet. */
    fun fromStored(context: Context, messages: List<Map<String, Any>>): List<IndexSourceMessage>? {
        val entries = ModelFacingMessageText.summarizerEntries(context, messages)
        if (entries.any { (stored, _) -> MessageIdentity.idOf(stored).isBlank() }) return null
        return entries.map { (stored, entry) ->
            IndexSourceMessage.from(stored, entry) ?: error("an entry always belongs to its message")
        }
    }
}

/**
 * Counted changes the app reports for each chat while it runs, so a save is
 * seen at once even before its file reaches the disk. Holds no content.
 */
object ConversationIndexChatChanges {
    private val counts = ConcurrentHashMap<String, AtomicLong>()

    fun changed(chatId: String) {
        counts.getOrPut(chatId) { AtomicLong() }.incrementAndGet()
    }

    fun count(chatId: String): Long = counts[chatId]?.get() ?: 0L
}

/** The real reader of the stored chats. Off the main thread. */
class SavedChatIndexSource(context: Context) : IndexChatSource {
    private val app = context.applicationContext
    private val chats get() = ChatPreferences.getChatPreferences()

    private data class Cached(val chatId: String, val version: String, val byId: Map<String, IndexSourceMessage>)

    /** The last chat read for the vector worker, which asks for one message at a time. */
    @Volatile private var cached: Cached? = null

    override fun read(chatId: String): IndexChatSource.Read {
        // A confirmed deletion whose save has not landed is not what the file holds yet;
        // its save reports the change again.
        if (ChatHistoryWriteOrder.shared.hasPending(chatId)) return IndexChatSource.Read.Unavailable
        val version = chatVersion(chatId)
        val result = try {
            chats.getChatByIdResult(app, chatId, DamagedChatOperation.MEMORY)
        } catch (_: Exception) {
            return IndexChatSource.Read.Unavailable
        }
        if (!ChatStorageHealth.isAuthoritative(result.state)) return IndexChatSource.Read.Unavailable
        val messages = try {
            IndexSourceMessages.fromStored(app, result.messages)
        } catch (_: Exception) {
            return IndexChatSource.Read.Unavailable
        } ?: return IndexChatSource.Read.WithoutPermanentIds
        return IndexChatSource.Read.Messages(version, messages)
    }

    override fun chatVersion(chatId: String): String {
        val file = SecurePrefs.encryptedFile(app, "chat_$chatId")
        val generation = try { ChatSetSourceGeneration.current(app) } catch (_: Exception) { -1L }
        return listOf(
            generation,
            if (file.exists()) file.length() else -1L,
            file.lastModified(),
            ConversationIndexChatChanges.count(chatId)
        ).joinToString(":")
    }

    override fun chatIdsNewestFirst(): List<String>? {
        val list = try {
            chats.getChatListResult(app, includeFirstMessage = false)
        } catch (_: Exception) {
            return null
        }
        if (!ChatStorageHealth.isAuthoritative(list.state)) return null
        return list.chats
            .map { row -> ChatPreferences.storedChatId(row) to newestTime(row) }
            .filter { it.first.isNotBlank() }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinct()
    }

    override fun currentMessage(chatId: String, messageId: String): IndexSourceMessage? {
        val version = chatVersion(chatId)
        cached?.takeIf { it.chatId == chatId && it.version == version }?.let { return it.byId[messageId] }
        val read = read(chatId) as? IndexChatSource.Read.Messages ?: return null
        val byId = read.messages.associateBy { it.messageId }
        cached = Cached(chatId, read.version, byId)
        return byId[messageId]
    }

    private fun newestTime(row: Map<String, String>): Long =
        maxOf(
            row[ChatPreferences.CHAT_CONTENT_MODIFIED_AT_KEY]?.toLongOrNull() ?: 0L,
            row["timestamp"]?.toLongOrNull() ?: 0L
        )
}
