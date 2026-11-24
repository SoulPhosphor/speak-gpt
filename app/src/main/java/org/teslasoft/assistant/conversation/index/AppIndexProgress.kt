/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

import android.content.Context
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModelStorage

/**
 * The indexing progress's view of the app (Step 12): the encrypted index,
 * the chat list and the Step 11 conditions. [work] runs on the index's
 * thread with the coordinator; [numbers] runs there too, beside a running
 * recovery's lock like the settings status.
 */
internal class AppIndexProgressSource(
    private val app: Context,
    private val chats: SavedChatIndexSource,
    private val damageFound: (Exception) -> Unit
) : IndexProgressSource {

    /** The cutting settings of the running catch-up, given by the updater. */
    @Volatile private var settingsKey: String? = null

    override fun work(chats: List<String>, settingsKey: String): IndexWork? {
        this.settingsKey = settingsKey
        val model = EmbeddingModelStorage.activeModel(app) ?: return null
        val store = if (ConversationIndexStore.exists(app)) ConversationIndexStore.get(app) else null
        val summaries = store?.chatSummaries(model.embeddingTag, model.dimensions).orEmpty()
        val waiting = store?.waitingPassages(model.embeddingTag, model.dimensions) ?: 0
        return IndexWorkFinder.work(chats, summaries, waiting, this.chats::chatVersion, settingsKey)
    }

    override fun numbers(counted: Map<String, CountedChat>): IndexingNumbers? {
        val key = settingsKey ?: return null
        val live = chats.chatIdsNewestFirst() ?: return null
        val model = EmbeddingModelStorage.activeModel(app) ?: return null
        return try {
            val (summaries, messages) = if (!ConversationIndexStore.exists(app)) emptyMap<String, ChatIndexSummary>() to
                emptyMap<String, MessageProgress>() else ConversationIndexAccess.shared {
                val store = ConversationIndexStore.get(app)
                store.chatSummaries(model.embeddingTag, model.dimensions) to
                    store.messageProgress(model.embeddingTag, model.dimensions)
            }
            IndexingProgressMath.numbers(live, summaries, messages, counted, chats::chatVersion, key)
        } catch (e: Exception) {
            if (IndexFailures.isDamage(e)) damageFound(e)
            null
        }
    }

    override fun conditions() = IndexingConditions(
        settingOn = ConversationIndexSetting.isOn(app),
        rebuilding = ConversationIndexConditions.rebuilding || ConversationIndexAccess.recovering,
        recoveryFailed = ConversationIndexConditions.recoveryFailed(app),
        modelUnavailable = ConversationIndexConditions.modelProblem(app) != null
    )
}

/** The numbers kept with the user's pause, in the index's own settings file (no message content). */
internal class SavedIndexingProgressPrefs(private val app: Context) : SavedIndexingProgress {

    override fun read(): IndexingNumbers? = try {
        val prefs = ConversationIndexPrefs.of(app)
        if (!prefs.contains(KEY_PERCENT)) null else IndexingNumbers(
            prefs.getInt(KEY_PERCENT, 0), prefs.getInt(KEY_INDEXED, 0), prefs.getInt(KEY_TOTAL, 0)
        )
    } catch (_: Exception) {
        null
    }

    override fun write(numbers: IndexingNumbers?) {
        try {
            val edit = ConversationIndexPrefs.of(app).edit()
            if (numbers == null) {
                edit.remove(KEY_PERCENT).remove(KEY_INDEXED).remove(KEY_TOTAL)
            } else {
                edit.putInt(KEY_PERCENT, numbers.percent).putInt(KEY_INDEXED, numbers.indexedChats)
                    .putInt(KEY_TOTAL, numbers.totalChats)
            }
            edit.apply()
        } catch (_: Exception) { }
    }

    private companion object {
        const val KEY_PERCENT = "paused_percent"
        const val KEY_INDEXED = "paused_indexed_chats"
        const val KEY_TOTAL = "paused_total_chats"
    }
}
