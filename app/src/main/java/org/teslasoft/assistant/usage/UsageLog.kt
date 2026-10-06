/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.usage

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID
import org.teslasoft.assistant.preferences.MessageIdentity

/** What a model request was for. Stored as [key]; never shown as-is. */
enum class UsageCategory(val key: String) {
    CHAT("chat"),
    ATTACHMENTS("attachments"),
    SUMMARIZATION("summarization"),
    TTS("tts");

    companion object {
        fun fromKey(key: String?): UsageCategory =
            entries.firstOrNull { it.key == key } ?: CHAT
    }
}

/**
 * One paid request, kept for the life of the chat. [id] is permanent;
 * [messageId] is the permanent id of the message the request produced or
 * served, when there is one. Neither depends on a message's position.
 */
data class UsageLogEntry(
    val id: String,
    val category: UsageCategory,
    val messageId: String?,
    val recordedAtMs: Long,
    val record: TurnUsageRecord
)

/**
 * A chat's usage log (owner ruling, Oct 6 2026). Usage already spent stays
 * counted when messages are deleted, regenerated, truncated by an earlier
 * edit, or compacted: entries are only ever added.
 *
 * [seeded] records that the chat's pre-log message records were copied in.
 * Until then an entry may be appended (for example by the summarizer) without
 * losing the older history, which [seed] merges in exactly once.
 */
data class UsageLogState(
    val seeded: Boolean,
    val entries: List<UsageLogEntry>
) {
    fun append(newEntries: List<UsageLogEntry>): UsageLogState =
        copy(entries = entries + newEntries)

    /** Copies the durable records already stored in [messages] once. */
    fun seed(messages: List<Map<String, Any>>, nowMs: Long): UsageLogState {
        if (seeded) return this
        val fromMessages = messages.flatMap { message ->
            val messageId = MessageIdentity.idOf(message).ifBlank { null }
            TokenUsageAccounting.durableRecordsOf(message).map { record ->
                UsageLog.entry(UsageCategory.CHAT, messageId, record, nowMs)
            }
        }
        return UsageLogState(seeded = true, entries = fromMessages + entries)
    }

    /**
     * Every logged request, plus the estimate for completed legacy replies
     * that predate durable records (those were never logged, so they can only
     * come from the messages still present).
     */
    fun summarize(
        messages: List<Map<String, Any>>,
        legacyEstimate: (assistantIndex: Int) -> TokenCounts,
        categories: Set<UsageCategory> = UsageCategory.entries.toSet()
    ): ConversationUsageSummary {
        val records = entries.filter { it.category in categories }.map { it.record }.toMutableList()
        if (UsageCategory.CHAT in categories) {
            messages.forEachIndexed { index, message ->
                TokenUsageAccounting.legacyRecordOf(message, index, legacyEstimate)?.let(records::add)
            }
        }
        return TokenUsageAccounting.aggregate(records)
    }
}

object UsageLog {
    private const val VERSION = 1
    private val gson = Gson()

    val EMPTY = UsageLogState(seeded = false, entries = emptyList())

    fun entry(
        category: UsageCategory,
        messageId: String?,
        record: TurnUsageRecord,
        nowMs: Long = System.currentTimeMillis()
    ) = UsageLogEntry(UUID.randomUUID().toString(), category, messageId, nowMs, record)

    /** Each field is written explicitly so a minified build cannot erase the
     * element types Gson would otherwise rely on. */
    fun encode(state: UsageLogState): String {
        val entries = JsonArray()
        state.entries.forEach { entry ->
            entries.add(JsonObject().apply {
                addProperty("id", entry.id)
                addProperty("category", entry.category.key)
                entry.messageId?.let { addProperty("messageId", it) }
                addProperty("recordedAtMs", entry.recordedAtMs)
                add("record", gson.toJsonTree(entry.record))
            })
        }
        return JsonObject().apply {
            addProperty("version", VERSION)
            addProperty("seeded", state.seeded)
            add("entries", entries)
        }.toString()
    }

    /** Null when [value] is not a readable log; blank means none yet. */
    fun decode(value: String?): UsageLogState? {
        if (value.isNullOrBlank()) return EMPTY
        return try {
            val root = JsonParser.parseString(value).asJsonObject
            val entries = root.getAsJsonArray("entries")?.mapNotNull { element ->
                val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val record = o.get("record")?.takeIf { it.isJsonObject }
                    ?.let { gson.fromJson(it, TurnUsageRecord::class.java) }
                    ?: return@mapNotNull null
                UsageLogEntry(
                    id = o.get("id")?.asString?.ifBlank { null } ?: return@mapNotNull null,
                    category = UsageCategory.fromKey(o.get("category")?.asString),
                    messageId = o.get("messageId")?.takeUnless { it.isJsonNull }?.asString,
                    recordedAtMs = o.get("recordedAtMs")?.asLong ?: 0L,
                    record = record
                )
            }.orEmpty()
            UsageLogState(seeded = root.get("seeded")?.asBoolean == true, entries = entries)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Reads and writes a chat's log in its settings file. The chat screen and the
 * summarizer can append concurrently, so every read-modify-write holds one
 * lock. An unreadable stored log is replaced rather than blocking new usage.
 */
object UsageLogStore {
    private val lock = Any()

    fun read(prefs: org.teslasoft.assistant.preferences.Preferences): UsageLogState =
        UsageLog.decode(prefs.getUsageLog()) ?: UsageLog.EMPTY

    /** Copies the chat's message records in once, before anything can delete them. */
    fun seed(
        prefs: org.teslasoft.assistant.preferences.Preferences,
        messages: List<Map<String, Any>>,
        nowMs: Long = System.currentTimeMillis()
    ): UsageLogState = synchronized(lock) {
        val current = read(prefs)
        if (current.seeded) return current
        val seeded = current.seed(messages, nowMs)
        prefs.commitUsageLog(UsageLog.encode(seeded))
        seeded
    }

    fun append(
        prefs: org.teslasoft.assistant.preferences.Preferences,
        entries: List<UsageLogEntry>
    ): Boolean {
        if (entries.isEmpty()) return true
        return synchronized(lock) {
            prefs.commitUsageLog(UsageLog.encode(read(prefs).append(entries)))
        }
    }
}
