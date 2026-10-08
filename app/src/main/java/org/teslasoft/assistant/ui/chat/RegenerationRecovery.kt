/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.ui.chat

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.ui.adapters.chat.ChatAdapter

/**
 * Keeps a regenerated turn's earlier versions when the regeneration never
 * produces a reply of its own (stopped before it began, blocked before it
 * was sent, or the app closed mid-way). Regenerate removes the current reply
 * before the new one is requested; without this, those cases lost the reply
 * and every earlier version with it.
 *
 * [Pending] is saved before the reply is removed, so recovery also works
 * after the app was closed. Recovery is safe to repeat: it only acts on the
 * exact state a regeneration leaves behind.
 */
object RegenerationRecovery {
    /**
     * The reply being regenerated and the message it answers.
     * [replyKeepsId] is false for a generated-image reply, whose
     * regeneration arrives as a new message with its own permanent id.
     */
    data class Pending(
        val original: HashMap<String, Any>,
        val precedingMessageId: String,
        val replyKeepsId: Boolean = true
    )

    sealed class Outcome {
        /** Nothing to change: the reply was never removed or was already settled. */
        object Unchanged : Outcome()
        /** The original reply, with its versions, goes back after the message it answers. */
        data class Restore(val original: HashMap<String, Any>) : Outcome()
        /** The new reply at the end becomes the newest version of the original. */
        data class Fold(val reply: HashMap<String, Any>) : Outcome()
    }

    private val gson = Gson()
    private const val KEY_ORIGINAL = "original"
    private const val KEY_PRECEDING = "precedingMessageId"
    private const val KEY_REPLY_KEEPS_ID = "replyKeepsId"

    fun pendingFor(messages: List<Map<String, Any>>, replyKeepsId: Boolean = true): Pending? {
        if (messages.size < 2) return null
        val last = messages.last()
        if (last["isBot"] != true) return null
        val precedingId = MessageIdentity.idOf(messages[messages.size - 2])
        if (precedingId.isBlank() || MessageIdentity.idOf(last).isBlank()) return null
        return Pending(HashMap(last), precedingId, replyKeepsId)
    }

    fun encode(pending: Pending): String = gson.toJson(
        mapOf(
            KEY_ORIGINAL to pending.original,
            KEY_PRECEDING to pending.precedingMessageId,
            KEY_REPLY_KEEPS_ID to pending.replyKeepsId
        )
    )

    /** Null for blank or unreadable text. */
    fun decode(value: String?): Pending? {
        if (value.isNullOrBlank()) return null
        return try {
            val type = object : TypeToken<HashMap<String, Any>>() {}.type
            val root: HashMap<String, Any> = gson.fromJson(value, type) ?: return null
            @Suppress("UNCHECKED_CAST")
            val original = (root[KEY_ORIGINAL] as? Map<String, Any>)?.let { HashMap(it) } ?: return null
            val preceding = root[KEY_PRECEDING]?.toString()?.ifBlank { null } ?: return null
            if (MessageIdentity.idOf(original).isBlank()) return null
            Pending(original, preceding, root[KEY_REPLY_KEEPS_ID] as? Boolean ?: true)
        } catch (_: Exception) {
            null
        }
    }

    /** The original's versions: its stored list, or its one reply as version one. */
    fun historyOf(original: Map<String, Any>): MutableList<HashMap<String, String>> {
        val existing = ChatAdapter.parseVariants(original[ChatAdapter.KEY_VARIANTS]?.toString())
        return if (existing.isNotEmpty()) existing else mutableListOf(ChatAdapter.snapshotVariant(original))
    }

    /** Adds [reply] as the newest, current version after [history]. */
    fun foldInto(reply: HashMap<String, Any>, history: MutableList<HashMap<String, String>>, messageId: String?) {
        history.add(ChatAdapter.snapshotVariant(reply))
        reply[ChatAdapter.KEY_VARIANTS] = ChatAdapter.variantsToJson(history)
        reply[ChatAdapter.KEY_CANONICAL_VARIANT] = (history.size - 1).toString()
        reply[ChatAdapter.KEY_DISPLAY_VARIANT] = (history.size - 1).toString()
        messageId?.let { reply[MessageIdentity.KEY] = it }
    }

    /**
     * What a saved [pending] regeneration means for [messages] as stored.
     * Restore only when the history still ends at the message the reply
     * answered; fold only an unversioned reply right after that message —
     * one that took the original's permanent id, or, for a generated-image
     * reply, any reply other than the original itself.
     */
    fun resolve(messages: List<HashMap<String, Any>>, pending: Pending): Outcome {
        val last = messages.lastOrNull() ?: return Outcome.Unchanged
        val originalId = MessageIdentity.idOf(pending.original)
        if (last["isBot"] != true) {
            return if (MessageIdentity.idOf(last) == pending.precedingMessageId) {
                Outcome.Restore(HashMap(pending.original))
            } else {
                Outcome.Unchanged
            }
        }
        val answersSameMessage = messages.size >= 2 &&
            MessageIdentity.idOf(messages[messages.size - 2]) == pending.precedingMessageId
        val isOriginal = last[ChatAdapter.KEY_MESSAGE_TIME]?.toString() ==
            pending.original[ChatAdapter.KEY_MESSAGE_TIME]?.toString() &&
            last["message"]?.toString() == pending.original["message"]?.toString()
        val unversioned = last[ChatAdapter.KEY_VARIANTS]?.toString().isNullOrBlank()
        val isRegeneratedReply = if (pending.replyKeepsId) {
            MessageIdentity.idOf(last) == originalId
        } else {
            MessageIdentity.idOf(last) != originalId
        }
        return if (answersSameMessage && !isOriginal && unversioned && isRegeneratedReply) {
            Outcome.Fold(last)
        } else {
            Outcome.Unchanged
        }
    }
}
