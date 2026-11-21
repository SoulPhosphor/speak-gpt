/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.scan

import android.content.Context
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.preferences.MessageNumbers
import org.teslasoft.assistant.preferences.MessageVisibility
import org.teslasoft.assistant.preferences.damaged.DamagedChatOperation
import org.teslasoft.assistant.util.Hash
import java.time.Instant

/** One chat message as the Memory Scanner sees it. The text is held in
 *  working memory only; nothing here is ever saved to the memory database. */
data class ScanMessage(
    val id: String,
    /** The permanent message number ([MessageNumbers]); null only for a
     *  message not yet saved with one. Gaps are normal. */
    val number: Long?,
    val role: String,
    val text: String,
    /** Visible to the AI, finished, and not blank. */
    val eligible: Boolean,
    /** Null when the message's scope is unknown (no recorded metadata). */
    val scope: ScanMessageScope?,
    val timeMillis: Long?,
    val model: String?,
    /** Changes whenever what the scanner would read from this message
     *  changes: its text, role, visibility or completion. */
    val fingerprint: String
) {
    val startedAt: String?
        get() = timeMillis?.let { runCatching { Instant.ofEpochMilli(it).toString() }.getOrNull() }
}

/**
 * Reads the original chat storage for the Memory Scanner (memory development
 * plan M.2, "The chat is the source"). The scanner reads the same current
 * messages the user sees: the latest content of each message UUID, the
 * selected reply version (a message's top-level fields always mirror it), and
 * only messages the AI is sent. A deleted message is simply absent.
 */
object ChatScanSource {

    sealed interface Load {
        data class Readable(val messages: List<ScanMessage>) : Load
        /** The stored history could not be decrypted or parsed. The chat
         *  storage layer has already recorded it in the Damaged Chat Log. */
        data object Damaged : Load
        /** Locked storage, a refused save, or another temporary condition. */
        data object Unavailable : Load
    }

    /**
     * Reads [chatId]. Messages without a permanent UUID receive one from
     * [MessageIdentity.ensure], and messages without a permanent number
     * receive one, saved in the original chat storage before anything is
     * scanned; when that save is refused the chat is reported
     * unavailable rather than scanned with identities that were not kept.
     */
    fun load(context: Context, chatId: String): Load {
        val chats = ChatPreferences.getChatPreferences()
        val result = try {
            chats.getChatByIdResult(context, chatId, DamagedChatOperation.MEMORY)
        } catch (_: Exception) {
            return Load.Unavailable
        }
        when (result.state) {
            ChatStorageHealth.ReadState.CORRUPT -> return Load.Damaged
            ChatStorageHealth.ReadState.LOCKED -> return Load.Unavailable
            else -> if (!ChatStorageHealth.isAuthoritative(result.state)) return Load.Unavailable
        }
        val messages = result.messages
        if (messages.any { MessageIdentity.idOf(it).isBlank() } || MessageNumbers.needsNumbers(messages)) {
            synchronized(ChatPreferences.CHAT_LIST_LOCK) {
                // Read again inside the lock so a save made in between is not
                // overwritten with the older copy read above.
                val fresh = try {
                    chats.getChatByIdResult(context, chatId, DamagedChatOperation.MEMORY)
                } catch (_: Exception) {
                    return Load.Unavailable
                }
                if (fresh.state == ChatStorageHealth.ReadState.CORRUPT) return Load.Damaged
                if (!ChatStorageHealth.isAuthoritative(fresh.state)) return Load.Unavailable
                // Saving also gives messages without a permanent number
                // theirs, in the chat's order.
                if (MessageIdentity.ensure(fresh.messages) || MessageNumbers.needsNumbers(fresh.messages)) {
                    val saved = chats.saveChatHistory(context, chatId, fresh.messages, synchronous = true)
                    if (saved != ChatStorageHealth.WriteOutcome.OK) return Load.Unavailable
                }
                return Load.Readable(fromMessages(fresh.messages))
            }
        }
        return Load.Readable(fromMessages(messages))
    }

    /**
     * Reads [chatId] without saving anything, for counts and statuses. A
     * message without a UUID yet is given a placeholder identity for this
     * read only; such a message cannot be behind a bookmark, which always
     * names real UUIDs. Null when the chat cannot be read.
     */
    fun peek(context: Context, chatId: String): List<ScanMessage>? {
        val result = try {
            ChatPreferences.getChatPreferences().getChatByIdResult(context, chatId, DamagedChatOperation.MEMORY)
        } catch (_: Exception) {
            return null
        }
        if (!ChatStorageHealth.isAuthoritative(result.state)) return null
        return fromMessages(result.messages.mapIndexed { index, m ->
            if (MessageIdentity.idOf(m).isNotBlank()) m
            else HashMap<String, Any>(m).apply { put(MessageIdentity.KEY, "unsaved:$index") }
        })
    }

    /** Pure conversion, in the chat's own order. Messages without an id are
     *  left out: identity is never guessed from text. */
    fun fromMessages(messages: List<Map<String, *>>): List<ScanMessage> =
        messages.mapNotNull { m ->
            val id = m[MessageIdentity.KEY]?.toString().orEmpty()
            if (id.isBlank()) return@mapNotNull null
            val assistant = m["isBot"] == true
            val text = m["message"]?.toString().orEmpty()
            val eligible = isEligible(m)
            ScanMessage(
                id = id,
                number = MessageNumbers.numberOf(m),
                role = if (assistant) "assistant" else "user",
                text = text,
                eligible = eligible,
                scope = ScanMessageScope.read(m),
                timeMillis = m[TIME_KEY]?.toString()?.toDoubleOrNull()?.toLong(),
                model = m[MODEL_KEY]?.toString()?.takeIf { it.isNotBlank() },
                fingerprint = fingerprint(assistant, text, eligible)
            )
        }

    /** The existing visibility rules: hidden messages and replies that did
     *  not finish are not read; a blank message has nothing to read. */
    fun isEligible(message: Map<String, *>): Boolean {
        val text = message["message"]?.toString().orEmpty()
        if (text.isBlank()) return false
        if (MessageVisibility.isHidden(message)) return false
        if (message["isBot"] == true &&
            !MessageCompletionState.isComplete(message[MessageCompletionState.KEY_STATE]?.toString())
        ) return false
        return true
    }

    fun fingerprint(assistant: Boolean, text: String, eligible: Boolean): String =
        Hash.hash((if (assistant) "a" else "u") + (if (eligible) "1" else "0") + "\u0000" + text)

    private const val TIME_KEY = "messageTime"
    private const val MODEL_KEY = "responseModel"
}
