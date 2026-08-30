/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.chatdeletion

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
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

    private data class Payload(
        val version: Int,
        val entries: List<ChatDeletionJournalEntry>
    )

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
        return try {
            val root = JsonParser.parseString(raw).asJsonObject
            if (root.get("version")?.asInt != SCHEMA_VERSION) {
                return ChatDeletionJournalRead.Unavailable
            }
            val entries = root.getAsJsonArray("entries")?.map { element ->
                Gson().fromJson(element, ChatDeletionJournalEntry::class.java)
            } ?: return ChatDeletionJournalRead.Unavailable
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
                Gson().toJson(Payload(SCHEMA_VERSION, entries))
            ).commit()
        } catch (_: Exception) {
            false
        }
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
