/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.chatdeletion

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID
import org.teslasoft.assistant.preferences.SecurePrefs

enum class ChatDeletionJournalStage { METADATA_PENDING, CLEANUP_PENDING }

data class ChatDeletionJournalEntry(
    val journalId: String,
    val chatIds: Set<String>,
    val folderId: String?,
    val decision: ChatDeletionDecision,
    val stage: ChatDeletionJournalStage,
    val candidateAssetFileNames: Set<String> = emptySet(),
    val createdAt: Long
)

sealed class ChatDeletionJournalRead {
    data class Available(val entries: List<ChatDeletionJournalEntry>) : ChatDeletionJournalRead()
    data object Unavailable : ChatDeletionJournalRead()
}

sealed class ChatDeletionJournalWrite {
    data class Success(val entry: ChatDeletionJournalEntry) : ChatDeletionJournalWrite()
    data object Failure : ChatDeletionJournalWrite()
}

/**
 * Encrypted, synchronously committed recovery journal. It contains only stable
 * chat/folder/image-file identities and never stores names or conversation data.
 */
class ChatDeletionJournalStore internal constructor(
    private val preferences: SharedPreferences,
    private val available: () -> Boolean = { true },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val FILE_NAME = "chat_deletion_journal"
        private const val DATA_KEY = "entries"
        private const val SHRINKER_EMPTY_BACKUP_KEY = "entries_shrinker_empty_v1"
        private const val SCHEMA_VERSION = 1
        private val LOCK = Any()

        fun get(context: Context): ChatDeletionJournalStore {
            val app = context.applicationContext
            val prefs = SecurePrefs.get(app, FILE_NAME)
            return ChatDeletionJournalStore(
                preferences = prefs,
                available = { !SecurePrefs.isLockedName(FILE_NAME) }
            )
        }
    }

    fun read(): ChatDeletionJournalRead = synchronized(LOCK) { readLocked() }

    fun create(
        chatIds: Set<String>,
        folderId: String?,
        decision: ChatDeletionDecision
    ): ChatDeletionJournalWrite = synchronized(LOCK) {
        if (chatIds.any { it.isBlank() } || decision == ChatDeletionDecision.CANCEL) {
            return@synchronized ChatDeletionJournalWrite.Failure
        }
        if (folderId != null && !isUuid(folderId)) return@synchronized ChatDeletionJournalWrite.Failure
        val current = (readLocked() as? ChatDeletionJournalRead.Available)?.entries
            ?: return@synchronized ChatDeletionJournalWrite.Failure
        val existingIds = current.mapTo(HashSet()) { it.journalId }
        var id = ""
        var attempts = 0
        while (id.isEmpty() && attempts < 100) {
            attempts++
            val candidate = idFactory()
            if (isUuid(candidate) && candidate !in existingIds) {
                id = candidate
            }
        }
        if (id.isEmpty()) return@synchronized ChatDeletionJournalWrite.Failure
        val entry = ChatDeletionJournalEntry(
            journalId = id,
            chatIds = chatIds.toSet(),
            folderId = folderId,
            decision = decision,
            stage = ChatDeletionJournalStage.METADATA_PENDING,
            createdAt = now()
        )
        if (writeLocked(current + entry)) ChatDeletionJournalWrite.Success(entry)
        else ChatDeletionJournalWrite.Failure
    }

    fun update(entry: ChatDeletionJournalEntry): Boolean = synchronized(LOCK) {
        val current = (readLocked() as? ChatDeletionJournalRead.Available)?.entries
            ?: return@synchronized false
        val old = current.firstOrNull { it.journalId == entry.journalId }
            ?: return@synchronized false
        val immutableIdentityMatches = old.chatIds == entry.chatIds &&
            old.folderId == entry.folderId &&
            old.decision == entry.decision &&
            old.createdAt == entry.createdAt
        val stageDoesNotRegress = old.stage == entry.stage ||
            (old.stage == ChatDeletionJournalStage.METADATA_PENDING &&
                entry.stage == ChatDeletionJournalStage.CLEANUP_PENDING)
        if (!immutableIdentityMatches || !stageDoesNotRegress ||
            !entry.candidateAssetFileNames.containsAll(old.candidateAssetFileNames)
        ) return@synchronized false
        writeLocked(current.map { if (it.journalId == entry.journalId) entry else it })
    }

    fun remove(journalId: String): Boolean = synchronized(LOCK) {
        val current = (readLocked() as? ChatDeletionJournalRead.Available)?.entries
            ?: return@synchronized false
        if (current.none { it.journalId == journalId }) return@synchronized true
        writeLocked(current.filterNot { it.journalId == journalId })
    }

    private fun readLocked(): ChatDeletionJournalRead {
        if (!available()) return ChatDeletionJournalRead.Unavailable
        val raw = try { preferences.getString(DATA_KEY, null) } catch (_: Exception) {
            return ChatDeletionJournalRead.Unavailable
        } ?: return ChatDeletionJournalRead.Available(emptyList())
        if (isEmptyObject(raw)) return quarantineShrinkerEmpty(raw)
        return try {
            val root = JsonParser.parseString(raw).asJsonObject
            if (root.get("version")?.asInt != SCHEMA_VERSION) {
                return ChatDeletionJournalRead.Unavailable
            }
            val encodedEntries = root.getAsJsonArray("entries")
                ?: return ChatDeletionJournalRead.Unavailable
            val entries = ArrayList<ChatDeletionJournalEntry>(encodedEntries.size())
            for (encodedEntry in encodedEntries) {
                entries.add(decodeEntry(encodedEntry.asJsonObject))
            }
            if (!valid(entries)) ChatDeletionJournalRead.Unavailable
            else ChatDeletionJournalRead.Available(entries)
        } catch (_: Exception) {
            ChatDeletionJournalRead.Unavailable
        }
    }

    private fun writeLocked(entries: List<ChatDeletionJournalEntry>): Boolean {
        if (!available() || !valid(entries)) return false
        return try {
            preferences.edit().putString(
                DATA_KEY,
                encode(entries)
            ).commit()
        } catch (_: Exception) {
            false
        }
    }

    /** The broken minified build could persist `{}` after R8 removed the
     * reflection-only wrapper fields. It contains no chat/image identities, so
     * it is never deletion authority. Preserve the exact bytes inside the same
     * encrypted journal and clear only the unusable active slot. */
    private fun quarantineShrinkerEmpty(raw: String): ChatDeletionJournalRead {
        val editor = preferences.edit()
        if (!preferences.contains(SHRINKER_EMPTY_BACKUP_KEY)) {
            editor.putString(SHRINKER_EMPTY_BACKUP_KEY, raw)
        }
        editor.remove(DATA_KEY)
        return if (editor.commit()) ChatDeletionJournalRead.Available(emptyList())
        else ChatDeletionJournalRead.Unavailable
    }

    private fun isEmptyObject(raw: String): Boolean = try {
        JsonParser.parseString(raw).asJsonObject.size() == 0
    } catch (_: Exception) {
        false
    }

    private fun encode(entries: List<ChatDeletionJournalEntry>): String {
        val encodedEntries = JsonArray()
        entries.forEach { entry ->
            encodedEntries.add(JsonObject().apply {
                addProperty("journalId", entry.journalId)
                add("chatIds", stringArray(entry.chatIds))
                if (entry.folderId == null) add("folderId", JsonNull.INSTANCE)
                else addProperty("folderId", entry.folderId)
                addProperty("decision", entry.decision.name)
                addProperty("stage", entry.stage.name)
                add("candidateAssetFileNames", stringArray(entry.candidateAssetFileNames))
                addProperty("createdAt", entry.createdAt)
            })
        }
        return JsonObject().apply {
            addProperty("version", SCHEMA_VERSION)
            add("entries", encodedEntries)
        }.toString()
    }

    private fun decodeEntry(encoded: JsonObject): ChatDeletionJournalEntry =
        ChatDeletionJournalEntry(
            journalId = encoded.get("journalId").asString,
            chatIds = stringSet(encoded.getAsJsonArray("chatIds")),
            folderId = encoded.get("folderId")?.takeUnless { it.isJsonNull }?.asString,
            decision = ChatDeletionDecision.valueOf(encoded.get("decision").asString),
            stage = ChatDeletionJournalStage.valueOf(encoded.get("stage").asString),
            candidateAssetFileNames = stringSet(encoded.getAsJsonArray("candidateAssetFileNames")),
            createdAt = encoded.get("createdAt").asLong
        )

    private fun stringArray(values: Set<String>): JsonArray = JsonArray().apply {
        values.sorted().forEach { add(it) }
    }

    private fun stringSet(values: JsonArray): Set<String> {
        val result = LinkedHashSet<String>(values.size())
        for (encodedValue in values) {
            val value = encodedValue.asString
            if (!result.add(value)) throw IllegalArgumentException("Duplicate journal value")
        }
        return result
    }

    private fun valid(entries: List<ChatDeletionJournalEntry>): Boolean {
        val ids = entries.map { it.journalId }
        return ids.size == ids.toSet().size && entries.all { entry ->
            isUuid(entry.journalId) &&
                entry.chatIds.none { it.isBlank() } &&
                (entry.folderId == null || isUuid(entry.folderId)) &&
                entry.decision != ChatDeletionDecision.CANCEL &&
                entry.createdAt >= 0L
        }
    }

    private fun isUuid(value: String): Boolean = try {
        UUID.fromString(value).toString().equals(value, ignoreCase = true)
    } catch (_: Exception) {
        false
    }
}
