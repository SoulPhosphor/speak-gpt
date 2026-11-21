/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.scan

import android.content.Context
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.damaged.DamagedChatOperation
import org.teslasoft.assistant.preferences.memory.MemoryLog
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.ScanChatProgress
import org.teslasoft.assistant.preferences.memory.ScanRangeRecord

/**
 * Moves the Memory Scanner off the legacy transcript store (memory
 * development plan M.2, "Removing the duplicate transcript store", step 2).
 * Runs at startup, after a restore of an older backup, and before any scan
 * while the migration is pending. For each chat, in order:
 *
 *  1. missing message UUIDs are assigned in the original chat storage;
 *  2. each live-captured turn that names a message still in the chat
 *     records its scope on that message (imported rows record nothing);
 *  3. completed, skipped and excluded progress becomes a UUID bookmark, or,
 *     when that cannot be done reliably, the chat is marked for an automatic
 *     complete rescan (owner decision, Stage 6A question 1);
 *  4. a scan live at upgrade time keeps a resumable range that restarts the
 *     chat from its bookmark, and run history becomes message ranges;
 *  5. only after those results are recorded is the chat's transcript text
 *     deleted.
 *
 * A chat whose storage cannot be read keeps its legacy rows and is tried
 * again next time; other chats are not held up. Text of a message no longer
 * in its chat is never copied anywhere.
 */
object ScanSourceMigration {

    private val lock = Any()

    /** True when the migration is complete (or nothing needed it). */
    fun runIfNeeded(context: Context): Boolean = synchronized(lock) {
        if (!MemoryStore.isProvisioned(context)) return true
        val store = MemoryStore.getInstance(context)
        if (store.scanSourceMigrationComplete()) return true
        return try {
            migrate(context, store)
        } catch (e: Exception) {
            MemoryLog.logAlways(
                context, "ScannerMigration", "error",
                "scanner source migration failed: ${e.javaClass.simpleName}: ${e.message} — " +
                    "legacy progress is kept and the migration retries at next start"
            )
            false
        }
    }

    private fun migrate(context: Context, store: MemoryStore): Boolean {
        val list = ChatPreferences.getChatPreferences().getChatListResult(context, includeFirstMessage = false)
        if (!ChatStorageHealth.isAuthoritative(list.state)) return false
        val liveChats = list.chats.map { ChatPreferences.storedChatId(it) }.toSet()
        val runTranscripts = store.legacyRunTranscriptIds().toMutableMap()
        val liveRanges = store.legacyLiveRanges()
        var allDone = true
        for (chatId in store.legacyTranscriptChatIds()) {
            val rows = store.legacyTranscriptsForChat(chatId)
            val rowIds = rows.map { it.transcriptId }.toSet()
            val runUpdates = LinkedHashMap<String, Pair<List<ScanSourceRanges.Range>, List<String>>>()
            if (chatId !in liveChats) {
                // The chat was deleted: there is nothing to map, and its
                // legacy text goes with the rest.
                for ((runId, ids) in runTranscripts) {
                    if (ids.any { it in rowIds }) runUpdates[runId] = emptyList<ScanSourceRanges.Range>() to ids.filter { it !in rowIds }
                }
                store.recordChatMigration(chatId, null, runUpdates.mapValues { (_, v) -> v.second }, emptyMap(), emptyList())
                runUpdates.forEach { (runId, v) -> runTranscripts[runId] = v.second }
                continue
            }
            val archivePaused = try {
                Preferences.getPreferences(context, chatId).isChatExcludedFromMemory()
            } catch (_: Exception) {
                false
            }
            val stamped = stampChat(context, chatId) { messages ->
                ScanSourceMigrationPlan.plan(
                    messages = messages,
                    rows = rows,
                    legacyBookmark = store.legacyBookmark(chatId),
                    archivePaused = archivePaused,
                    personaIdForCompanion = { id -> store.getCompanion(id)?.appCharacterId?.takeIf { it.isNotBlank() } }
                )
            }
            if (stamped == null) {
                allDone = false
                MemoryLog.logAlways(
                    context, "ScannerMigration", "warn",
                    "chat=$chatId could not be read during the scanner migration; its earlier scan " +
                        "progress is kept and the migration retries at next start"
                )
                continue
            }
            val (ids, plan) = stamped
            if (plan.needsFullRescan) {
                MemoryLog.logAlways(
                    context, "ScannerMigration", "warn",
                    "chat=$chatId: earlier scan progress could not be converted to message references; " +
                        "a complete rescan of this conversation is scheduled"
                )
            }
            val bookmarkAnchors = ScanPosition.anchors(ids, plan.bookmarkIndex)
            val progress = ScanChatProgress(chatId, bookmarkAnchors, plan.needsFullRescan)
            val adopted = ArrayList<ScanRangeRecord>()
            for ((ordinal, live) in liveRanges.withIndex()) {
                if (live.second != chatId || plan.needsFullRescan) continue
                val end = plan.claimedEnds[live.first] ?: continue
                if (end <= plan.bookmarkIndex) continue
                adopted.add(
                    ScanRangeRecord(
                        rangeId = MemoryStore.newId("range-"),
                        runId = live.first,
                        chatId = chatId,
                        ordinal = ordinal,
                        kind = ScanRangeRecord.KIND_SCAN,
                        startAnchors = bookmarkAnchors,
                        endAnchors = ScanPosition.anchors(ids, end)
                    )
                )
            }
            val sourceRanges = HashMap<String, List<ScanSourceRanges.Range>>()
            for ((runId, runIds) in runTranscripts) {
                val mine = runIds.filter { it in rowIds }
                if (mine.isEmpty()) continue
                val spans = mine.mapNotNull { plan.spans[it] }
                val ranges = if (spans.isEmpty()) emptyList() else listOf(
                    ScanSourceRanges.Range(
                        chatId,
                        ScanPosition.anchors(ids, spans.minOf { it.first } - 1),
                        ScanPosition.anchors(ids, spans.maxOf { it.last })
                    )
                )
                runUpdates[runId] = ranges to runIds.filter { it !in rowIds }
                sourceRanges[runId] = ranges
            }
            store.recordChatMigration(
                chatId, progress,
                runUpdates.mapValues { (_, v) -> v.second },
                sourceRanges,
                adopted
            )
            runUpdates.forEach { (runId, v) -> runTranscripts[runId] = v.second }
        }
        if (allDone && store.legacyTranscriptChatIds().isEmpty()) {
            store.finishScanSourceMigration()
            return true
        }
        return false
    }

    /**
     * Reads [chatId] under the chat-list lock, assigns missing UUIDs,
     * records the planned scopes and saves when anything changed. Null when
     * the chat cannot be read or saved; nothing is recorded then.
     */
    private fun stampChat(
        context: Context,
        chatId: String,
        plan: (List<ScanSourceMigrationPlan.Message>) -> ScanSourceMigrationPlan.Result
    ): Pair<List<String>, ScanSourceMigrationPlan.Result>? = synchronized(ChatPreferences.CHAT_LIST_LOCK) {
        val chats = ChatPreferences.getChatPreferences()
        val history = chats.getChatByIdResult(context, chatId, DamagedChatOperation.MEMORY)
        if (!ChatStorageHealth.isAuthoritative(history.state)) return null
        val maps = history.messages
        var changed = MessageIdentity.ensure(maps) or
            org.teslasoft.assistant.preferences.MessageNumbers.needsNumbers(maps)
        val messages = maps.map { m ->
            ScanSourceMigrationPlan.Message(
                id = MessageIdentity.idOf(m),
                timeMillis = m["messageTime"]?.toString()?.toDoubleOrNull()?.toLong(),
                hasScope = ScanMessageScope.has(m),
                text = m["message"]?.toString()
            )
        }
        val result = plan(messages)
        for (m in maps) {
            val scope = result.scopes[MessageIdentity.idOf(m)] ?: continue
            if (ScanMessageScope.write(m, scope)) changed = true
        }
        if (changed && chats.saveChatHistory(context, chatId, maps, synchronous = true) !=
            ChatStorageHealth.WriteOutcome.OK
        ) return null
        return messages.map { it.id } to result
    }
}
