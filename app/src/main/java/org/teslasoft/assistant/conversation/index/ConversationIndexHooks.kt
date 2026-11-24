/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * Whether conversation indexing runs. **Owner (2026-11-24):** the Step 10
 * hookups stay inert until Step 11's Index Conversations setting turns them
 * on, so nothing changes in normal use before that setting exists. This is
 * not a setting and is not saved: it is off whenever the app starts, and
 * only tests turn it on.
 */
object ConversationIndexActivation {
    @Volatile var isOn: Boolean = false
}

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
 * when indexing is off it does nothing, and otherwise it only records the
 * change and hands the work to the index's background thread. Nothing here
 * can throw into, slow down or change the calling chat operation
 * (G.5a rule 10).
 */
object ConversationIndexHooks {

    /** The chat's history was saved: new messages, a finished reply, edits, deletions, hiding or showing, another version. */
    fun chatSaved(context: Context, chatId: String) = hand(context) {
        ConversationIndexChatChanges.changed(chatId)
        it.chatChanged(chatId)
    }

    fun chatsDeleted(context: Context, chatIds: Collection<String>) = hand(context) {
        chatIds.forEach(ConversationIndexChatChanges::changed)
        it.chatsDeleted(chatIds.toList())
    }

    /** A rename moved the chat to a new ID (the older-chat rename path). */
    fun chatRenamed(context: Context, oldChatId: String, newChatId: String) = hand(context) {
        ConversationIndexChatChanges.changed(oldChatId)
        ConversationIndexChatChanges.changed(newChatId)
        it.chatMoved(oldChatId, newChatId)
    }

    /** A backup was restored or merged into the chats. */
    fun chatSetReplaced(context: Context) = hand(context) { it.chatSetReplaced() }

    fun embeddingModelChanged(context: Context) = hand(context) { it.embeddingModelChanged() }

    fun appStarted(context: Context) = hand(context) { it.appStarted() }

    private inline fun hand(context: Context, block: (ConversationIndexCoordinator) -> Unit) {
        if (!ConversationIndexActivation.isOn) return
        try {
            block(coordinator(context))
        } catch (_: Exception) {
            // The index is derived; a failure here never reaches the chat.
        }
    }

    @Volatile private var instance: ConversationIndexCoordinator? = null

    /** Created on first use, so nothing is started while indexing is off. */
    private val executor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "conversation-index-upkeep").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }

    private fun coordinator(context: Context): ConversationIndexCoordinator =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    /** Creating it opens nothing; the store and the model are opened on the background threads. */
    private fun build(app: Context): ConversationIndexCoordinator {
        val source = SavedChatIndexSource(app)
        val runner = IndexTaskRunner.on(executor)
        val isOn = { ConversationIndexActivation.isOn }
        val vectors = IndexVectorPasses(
            runPass = { PassageEmbeddingWorker.forInstalledModel(app, source)?.runPass { !isOn() } },
            runner = IndexTaskRunner.on(vectorExecutor),
            isOn = isOn
        )
        return ConversationIndexCoordinator(
            source = source,
            storage = { ConversationIndexStore.get(app) },
            maintenance = { ConversationIndexStore.get(app) },
            updaterFor = {
                SearchTextReader.forInstalledModel(app)?.let { reader ->
                    ConversationIndexUpdater(
                        ConversationIndexStore.get(app), reader,
                        ConversationIndexSettings.TARGET_TOKENS.coerceAtMost(reader.hardLimit),
                        ConversationIndexSettings.OVERLAP_WORDS
                    )
                }
            },
            requestVectors = vectors::request,
            runner = runner,
            isOn = isOn
        )
    }

    /** Vector passes run on their own thread, so a long pass never holds up the upkeep of the chats. */
    private val vectorExecutor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "conversation-index-vectors").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }
}
