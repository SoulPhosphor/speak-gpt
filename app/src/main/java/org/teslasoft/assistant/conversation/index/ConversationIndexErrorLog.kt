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

    /** Includes underlying and suppressed cleanup failures, with cycle protection. */
    fun cause(e: Throwable): String = org.teslasoft.assistant.util.DiagnosticCause.describe(e)

    fun deletionFailed(result: IndexDeleteResult) =
        "Conversation index deletion failed while ${result.stage!!.description}: ${cause(result.cause!!)}. " +
            "Deletion is incomplete. Indexing is off. Retry: delete the conversation index again."

    fun operationFailed(operation: IndexReadOperation, detail: String) = when (operation) {
        IndexReadOperation.CHECK -> "Conversation index database integrity check failed: $detail. " +
            "Database health is unknown; this failure does not confirm damage. Retry: run the database check again."
        IndexReadOperation.COUNTS -> "Conversation index count could not be read: $detail. " +
            "The current count is unavailable. Retry: automatically while indexing is on, or reopen the indexing settings."
        IndexReadOperation.UPKEEP -> "Conversation index upkeep failed: $detail. " +
            "Pending changes may not be indexed. Retry: while indexing is on, automatically at the next chat change or app start."
        IndexReadOperation.VECTOR_START -> "Conversation search vector preparation could not start: $detail. " +
            "Search vectors remain incomplete. Retry: automatically at the next chat change, model change or app start."
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
        "Conversation index rebuild failed: $cause. Indexing was turned off. " +
            "Retry: turn indexing on again in RAG active memory settings."

    fun chatFailed(chatId: String, cause: String) =
        "Conversation indexing failed for chat $chatId: $cause. Index remains incomplete. " +
            "Retry: while indexing is on, automatically when the chat next changes or the app next starts."

    /** Vectors that will never be read for this text: the model input does not fit. */
    fun vectorsTooLong(count: Int, tokenLimit: Int) =
        "Conversation search vector preparation failed for ${passages(count)}: the model input is longer than the " +
            "embedding model's reading limit of $tokenLimit tokens (prefix and special tokens included). " +
            "Effect: these passages cannot be found by meaning search, and their conversations are not counted as " +
            "indexed. Retry: none; permanently failed. A passage is read again only if its message's text or the " +
            "embedding model changes."

    /** A vector pass stopped by a failure; the work stays waiting. */
    fun vectorsInterrupted(waiting: Int?, cause: String) =
        "Conversation search vector preparation failed for ${waiting?.let(::passages) ?: "an unknown number of passages (the waiting count could not be read)"}: $cause. " +
            "Effect: unfinished passages stay waiting and cannot be found by meaning search yet. " +
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
    private val loggedOperations = java.util.concurrent.ConcurrentHashMap<IndexReadOperation, String>()

    fun operationFailed(operation: IndexReadOperation, detail: String) {
        if (loggedOperations.put(operation, detail) != detail) {
            write("error", ConversationIndexErrorLog.operationFailed(operation, detail))
        }
    }

    fun operationSucceeded(operation: IndexReadOperation) { loggedOperations.remove(operation) }

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
        operationSucceeded(IndexReadOperation.UPKEEP)
    }

    fun indexFailed(cause: Exception) {
        if (IndexFailures.isDamage(cause)) damageFound(ConversationIndexErrorLog.cause(cause))
        else operationFailed(IndexReadOperation.UPKEEP, ConversationIndexErrorLog.cause(cause))
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
                    write("error", ConversationIndexErrorLog.vectorsInterrupted(waiting(), cause))
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
