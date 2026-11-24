/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

/**
 * The conversation index's Error Log entries (memory development plan G.13a;
 * wording approved by the owner on 2026-11-24). Each entry names what failed,
 * the technical cause, the effect and whether it is retried. No message text,
 * conversation text or other content is ever written: only chat IDs, counts,
 * exception types and their messages.
 */
object ConversationIndexErrorLog {
    const val TAG = "ConversationIndex"

    /** The exception's type and message, with up to two underlying causes. */
    fun cause(e: Throwable): String {
        val parts = ArrayList<String>()
        var current: Throwable? = e
        while (current != null && parts.size < 3) {
            parts.add(current.javaClass.name + (current.message?.let { ": $it" } ?: ""))
            current = current.cause?.takeIf { it !== current }
        }
        return parts.joinToString("; caused by ")
    }

    private fun passages(count: Int) = if (count == 1) "1 passage" else "$count passages"
    private fun conversations(count: Int) = if (count == 1) "1 conversation" else "$count conversations"
    private fun vectors(count: Int) = if (count == 1) "1 existing vector" else "$count existing vectors"

    fun damageDetected(cause: String) =
        "Conversation index database problem detected: $cause. Automatic recovery started."

    /**
     * @param queued conversations in the chat list, all of which the rebuilt index catches up.
     * @param retained made vectors carried into the rebuilt index.
     * @param notRead why vectors of the damaged index could not be read, or null when every readable one was read.
     */
    fun rebuildCompleted(queued: Int, retained: Int, notRead: String?): String = buildString {
        append("Conversation index rebuild completed. ")
        append(conversations(queued)).append(" queued for indexing. ")
        append(vectors(retained)).append(" retained.")
        if (notRead != null) {
            append(" Vectors that could not be kept (").append(notRead)
            append(") are made again as the conversations are indexed.")
        }
    }

    fun rebuildFailed(cause: String) =
        "Conversation index rebuild failed: $cause. Index Conversations was disabled. " +
            "Recovery requires another indexing attempt."

    fun chatFailed(chatId: String, cause: String) =
        "Conversation indexing failed for chat $chatId: $cause. Index remains incomplete. " +
            "Retry: automatically when the chat next changes, or at the next app start."

    /** Vectors that will never be read for this text: the model input does not fit. */
    fun vectorsTooLong(count: Int, tokenLimit: Int) =
        "Conversation search vector preparation failed for ${passages(count)}: the model input is longer than the " +
            "embedding model's reading limit of $tokenLimit tokens (prefix and special tokens included). " +
            "Effect: these passages cannot be found by meaning search, and their conversations are not counted as " +
            "indexed. Retry: none; permanently failed. A passage is read again only if its message's text or the " +
            "embedding model changes."

    /** A vector pass stopped by a failure; the work stays waiting. */
    fun vectorsInterrupted(waiting: Int, cause: String) =
        "Conversation search vector preparation failed for ${passages(waiting)}: $cause. " +
            "Effect: they stay waiting and cannot be found by meaning search yet. " +
            "Retry: automatically on the next indexing pass (after the next chat change, embedding model change " +
            "or app start)."

    fun modelUnavailable(cause: String) =
        "Conversation indexing paused because the on-device search model is unavailable: $cause. " +
            "Indexing will resume when the model is available."
}

/**
 * Decides which failures reach the Error Log, so a failure that goes on is
 * written once, not on every retry (Owner, 2026-11-24): a chat's failure
 * once per cause until the chat is indexed again; a failure of the whole
 * index (it cannot be opened now) once until a chat is indexed again; a
 * stopped vector pass once per cause until a pass finishes; and permanently
 * failed vectors once, when they fail. Damage is handed to [damageFound]
 * instead, which starts the automatic recovery.
 */
class ConversationIndexReporter(
    private val write: (level: String, message: String) -> Unit,
    private val damageFound: (cause: String) -> Unit
) {
    private val loggedChatFailures = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var loggedIndexFailure: String? = null
    @Volatile private var loggedVectorFailure: String? = null

    fun chatFailed(chatId: String, cause: Exception) {
        val text = ConversationIndexErrorLog.cause(cause)
        if (IndexFailures.isDamage(cause)) {
            damageFound(text)
            return
        }
        if (isIndexWide(cause)) {
            // Every chat fails the same way while the index cannot be opened: one entry.
            synchronized(this) {
                if (loggedIndexFailure == text) return
                loggedIndexFailure = text
            }
        } else if (loggedChatFailures.put(chatId, text) == text) {
            return
        }
        write("error", ConversationIndexErrorLog.chatFailed(chatId, text))
    }

    fun chatIndexed(chatId: String) {
        loggedChatFailures.remove(chatId)
        loggedIndexFailure = null
    }

    fun indexFailed(cause: Exception) {
        if (IndexFailures.isDamage(cause)) damageFound(ConversationIndexErrorLog.cause(cause))
    }

    /** [newCondition]: the paused condition was not already recorded with this cause. */
    fun modelUnavailable(newCondition: Boolean, detail: String) {
        if (newCondition) write("error", ConversationIndexErrorLog.modelUnavailable(detail))
    }

    /** One vector pass's outcome; [waiting] counts the passages still waiting, or null when it cannot. */
    fun vectorPass(outcome: PassageEmbeddingWorker.Outcome, tokenLimit: Int, waiting: () -> Int?) {
        val counts = when (outcome) {
            is PassageEmbeddingWorker.Outcome.Finished -> outcome.counts
            is PassageEmbeddingWorker.Outcome.Paused -> outcome.counts
            is PassageEmbeddingWorker.Outcome.Cancelled -> outcome.counts
            is PassageEmbeddingWorker.Outcome.Interrupted -> outcome.counts
            PassageEmbeddingWorker.Outcome.AlreadyRunning -> null
        }
        // Each failed vector is failed once and never retried, so this is never repeated.
        if (counts != null && counts.failed > 0) {
            write("error", ConversationIndexErrorLog.vectorsTooLong(counts.failed, tokenLimit))
        }
        when (outcome) {
            is PassageEmbeddingWorker.Outcome.Finished -> loggedVectorFailure = null
            is PassageEmbeddingWorker.Outcome.Interrupted -> {
                val cause = ConversationIndexErrorLog.cause(outcome.cause)
                if (IndexFailures.isDamage(outcome.cause)) {
                    damageFound(cause)
                } else if (cause != loggedVectorFailure) {
                    loggedVectorFailure = cause
                    write("error", ConversationIndexErrorLog.vectorsInterrupted(waiting() ?: 0, cause))
                }
            }
            else -> Unit
        }
    }

    /** Failures of opening the index itself, not of one chat. */
    private fun isIndexWide(cause: Exception): Boolean =
        cause is IndexKeyUnavailableException ||
            cause is android.database.sqlite.SQLiteCantOpenDatabaseException ||
            cause is android.database.sqlite.SQLiteDiskIOException ||
            cause is android.database.sqlite.SQLiteFullException ||
            cause is android.database.sqlite.SQLiteDatabaseLockedException
}
