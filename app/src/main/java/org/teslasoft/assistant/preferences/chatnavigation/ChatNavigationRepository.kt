/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.chatnavigation

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.util.Locale
import java.util.UUID
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.SnapshotRegistry

/**
 * Authoritative lightweight organization store for the future drawer.
 *
 * Folder records and chat rows share the encrypted `chat_list` file so a
 * folder plus its verified membership can be removed by one synchronous
 * SharedPreferences commit while [ChatPreferences.CHAT_LIST_LOCK] is held.
 */
class ChatNavigationRepository internal constructor(
    private val chatListPreferences: SharedPreferences,
    private val presentationPreferences: SharedPreferences,
    private val readChatList: () -> ChatPreferences.ChatListResult,
    private val preserveCorruptFolders: (String) -> Unit,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val FOLDERS_KEY = "folders"
        const val SCHEMA_VERSION_KEY = "chat_navigation_schema_version"
        const val FOLDER_ID_KEY = "folder_id"

        private const val FOLDERS_EXPANDED_KEY = "chat_navigation.folders_expanded"
        private const val FOLDER_EXPANDED_PREFIX = "chat_navigation.folder_expanded."

        fun get(context: Context): ChatNavigationRepository {
            val app = context.applicationContext
            val chatPreferences = ChatPreferences.getChatPreferences()
            return ChatNavigationRepository(
                chatListPreferences = SecurePrefs.get(app, "chat_list"),
                presentationPreferences = app.getSharedPreferences("settings", Context.MODE_PRIVATE),
                readChatList = {
                    chatPreferences.getChatListResult(app, includeFirstMessage = false)
                },
                preserveCorruptFolders = { raw -> preserveCorruptFolderJson(app, raw) }
            )
        }

        private val preservedFolderPayloads = java.util.Collections.synchronizedSet(HashSet<String>())

        private fun preserveCorruptFolderJson(context: Context, raw: String) {
            // Keep the original in place so corruption can never masquerade as
            // an empty folder catalog. Back up/report once per payload/process.
            if (!preservedFolderPayloads.add(raw)) return
            val backupName = "chat_list_corrupt_${SnapshotRegistry.uniqueSuffix()}"
            val committed = try {
                SecurePrefs.get(context, backupName).edit()
                    .putString(FOLDERS_KEY, raw).commit()
            } catch (_: Exception) {
                false
            }
            if (committed) {
                SnapshotRegistry.record(
                    context,
                    backupName,
                    "chat_list",
                    SnapshotRegistry.ORIGIN_CORRUPT_JSON,
                    "unparseable_folders_json"
                )
            }
            Logger.log(
                context,
                ChatPreferences.CORRUPT_DATA_LOG_TYPE,
                "ChatNavigationRepository",
                "error",
                "Stored folder metadata failed validation. A backup copy was kept when storage allowed it; folder changes are blocked and the chat list was not modified."
            )
        }
    }

    private data class FolderCatalog(val version: Int, val folders: List<FolderRecord>)

    fun migrateSchema(): ChatNavigationResult<Unit> = synchronized(ChatPreferences.CHAT_LIST_LOCK) {
        readAuthoritativeChats() ?: return@synchronized unavailable()
        val marker = try { chatListPreferences.getInt(SCHEMA_VERSION_KEY, 0) } catch (_: Exception) {
            return@synchronized unavailable()
        }
        if (marker > SCHEMA_VERSION) return@synchronized failure(ChatNavigationFailure.UNSUPPORTED_SCHEMA)

        val rawPresent = try { chatListPreferences.contains(FOLDERS_KEY) } catch (_: Exception) {
            return@synchronized unavailable()
        }
        if (rawPresent) {
            when (val catalog = readFolders()) {
                is FolderRead.Ok -> Unit
                FolderRead.Corrupt -> return@synchronized failure(ChatNavigationFailure.CORRUPT_FOLDERS)
                FolderRead.Unsupported -> return@synchronized failure(ChatNavigationFailure.UNSUPPORTED_SCHEMA)
                FolderRead.Unavailable -> return@synchronized unavailable()
                FolderRead.Missing -> Unit
            }
        }
        if (marker == SCHEMA_VERSION && rawPresent) return@synchronized success(Unit)

        val editor = chatListPreferences.edit()
        if (!rawPresent) editor.putString(FOLDERS_KEY, encodeFolders(emptyList()))
        editor.putInt(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
        if (editor.commit()) success(Unit) else failure(ChatNavigationFailure.COMMIT_FAILED)
    }

    fun snapshot(locale: Locale = Locale.getDefault()): ChatNavigationResult<ChatNavigationSnapshot> =
        synchronized(ChatPreferences.CHAT_LIST_LOCK) {
            val migration = migrateSchema()
            if (migration is ChatNavigationResult.Failure) return@synchronized migration
            val result = readChatList()
            if (!ChatStorageHealth.isAuthoritative(result.state)) return@synchronized unavailable()
            val folders = when (val read = readFolders()) {
                is FolderRead.Ok -> read.folders
                FolderRead.Corrupt -> return@synchronized failure(ChatNavigationFailure.CORRUPT_FOLDERS)
                FolderRead.Unsupported -> return@synchronized failure(ChatNavigationFailure.UNSUPPORTED_SCHEMA)
                else -> return@synchronized unavailable()
            }
            val items = result.chats.map(::toNavigationItem)
            success(ChatNavigationProjection.build(items, folders, result.state, locale))
        }

    fun createFolder(proposedName: String): ChatNavigationResult<FolderRecord> = mutate { chats, folders ->
        when (val validation = FolderNamePolicy.validate(proposedName, folders)) {
            FolderNamePolicy.Validation.Blank -> MutationDecision.Fail(ChatNavigationFailure.BLANK_NAME)
            FolderNamePolicy.Validation.Duplicate -> MutationDecision.Fail(ChatNavigationFailure.DUPLICATE_NAME)
            is FolderNamePolicy.Validation.Valid -> {
                val existingIds = folders.mapTo(HashSet()) { it.id }
                var id: String
                var attempts = 0
                do {
                    id = idFactory()
                    attempts++
                    if (!isUuid(id) || attempts > 100) {
                        return@mutate MutationDecision.Fail(ChatNavigationFailure.INVALID_FOLDER_ID)
                    }
                } while (id in existingIds)
                val folder = FolderRecord(id, validation.trimmedName, pinned = false)
                MutationDecision.Write(folder, chats, folders + folder, writeChats = false, writeFolders = true)
            }
        }
    }

    fun renameFolder(folderId: String, proposedName: String): ChatNavigationResult<FolderRecord> =
        mutate { chats, folders ->
            val current = folders.firstOrNull { it.id == folderId }
                ?: return@mutate MutationDecision.Fail(ChatNavigationFailure.NOT_FOUND)
            when (val validation = FolderNamePolicy.validate(proposedName, folders, folderId)) {
                FolderNamePolicy.Validation.Blank -> MutationDecision.Fail(ChatNavigationFailure.BLANK_NAME)
                FolderNamePolicy.Validation.Duplicate -> MutationDecision.Fail(ChatNavigationFailure.DUPLICATE_NAME)
                is FolderNamePolicy.Validation.Valid -> {
                    // Identity is immutable: rename replaces only the label.
                    val renamed = current.copy(name = validation.trimmedName)
                    MutationDecision.Write(
                        renamed,
                        chats,
                        folders.map { if (it.id == folderId) renamed else it },
                        writeChats = false,
                        writeFolders = renamed != current
                    )
                }
            }
        }

    fun setFolderPinned(folderId: String, pinned: Boolean): ChatNavigationResult<FolderRecord> =
        mutate { chats, folders ->
            val current = folders.firstOrNull { it.id == folderId }
                ?: return@mutate MutationDecision.Fail(ChatNavigationFailure.NOT_FOUND)
            val updated = current.copy(pinned = pinned)
            MutationDecision.Write(
                updated,
                chats,
                folders.map { if (it.id == folderId) updated else it },
                writeChats = false,
                writeFolders = updated != current
            )
        }

    fun moveChat(chatId: String, folderId: String?): ChatNavigationResult<ChatNavigationItem> =
        mutate { chats, folders ->
            if (folderId != null && folders.none { it.id == folderId }) {
                return@mutate MutationDecision.Fail(ChatNavigationFailure.NOT_FOUND)
            }
            val row = chats.firstOrNull { ChatPreferences.storedChatId(it) == chatId }
                ?: return@mutate MutationDecision.Fail(ChatNavigationFailure.NOT_FOUND)
            val changed = row[FOLDER_ID_KEY] != folderId ||
                (folderId == null && row.containsKey(FOLDER_ID_KEY))
            if (folderId == null) row.remove(FOLDER_ID_KEY) else row[FOLDER_ID_KEY] = folderId
            MutationDecision.Write(
                toNavigationItem(row), chats, folders,
                writeChats = changed, writeFolders = false
            )
        }

    fun setChatPinned(chatId: String, pinned: Boolean): ChatNavigationResult<ChatNavigationItem> =
        mutate { chats, folders ->
            val row = chats.firstOrNull { ChatPreferences.storedChatId(it) == chatId }
                ?: return@mutate MutationDecision.Fail(ChatNavigationFailure.NOT_FOUND)
            val changed = row["pinned"] != pinned.toString()
            row["pinned"] = pinned.toString()
            MutationDecision.Write(
                toNavigationItem(row), chats, folders,
                writeChats = changed, writeFolders = false
            )
        }

    /**
     * Phase 3 primitive. It changes metadata only; history/image deletion is
     * deliberately outside Phase 2. When [folderId] is supplied, the caller's
     * chat IDs must exactly match the folder's authoritative current members.
     */
    fun removeChatMetadataBatch(
        chatIds: Set<String>,
        folderId: String? = null
    ): ChatNavigationResult<BatchMetadataRemoval> = synchronized(ChatPreferences.CHAT_LIST_LOCK) {
        val migration = migrateSchema()
        if (migration is ChatNavigationResult.Failure) return@synchronized migration
        val chats = readAuthoritativeChats() ?: return@synchronized unavailable()
        val folders = when (val read = readFolders()) {
            is FolderRead.Ok -> read.folders
            FolderRead.Corrupt -> return@synchronized failure(ChatNavigationFailure.CORRUPT_FOLDERS)
            FolderRead.Unsupported -> return@synchronized failure(ChatNavigationFailure.UNSUPPORTED_SCHEMA)
            else -> return@synchronized unavailable()
        }
        val existingIds = chats.mapTo(HashSet()) { ChatPreferences.storedChatId(it) }
        if (!existingIds.containsAll(chatIds)) {
            return@synchronized failure(ChatNavigationFailure.STALE_MEMBERSHIP)
        }
        if (folderId != null) {
            if (folders.none { it.id == folderId }) return@synchronized failure(ChatNavigationFailure.NOT_FOUND)
            val currentMembers = chats.filter { it[FOLDER_ID_KEY] == folderId }
                .mapTo(HashSet()) { ChatPreferences.storedChatId(it) }
            if (currentMembers != chatIds) {
                return@synchronized failure(ChatNavigationFailure.STALE_MEMBERSHIP)
            }
        }

        val remainingChats = chats.filterNotTo(arrayListOf()) {
            ChatPreferences.storedChatId(it) in chatIds
        }
        val remainingFolders = if (folderId == null) folders else folders.filterNot { it.id == folderId }
        val editor = chatListPreferences.edit()
            .putString("data", Gson().toJson(remainingChats))
        if (folderId != null) editor.putString(FOLDERS_KEY, encodeFolders(remainingFolders))
        if (!editor.commit()) return@synchronized failure(ChatNavigationFailure.COMMIT_FAILED)

        // Presentation cleanup happens only after the durable organization
        // transaction. Failure here cannot resurrect or partially delete data.
        if (folderId != null) presentationPreferences.edit()
            .remove(FOLDER_EXPANDED_PREFIX + folderId).commit()
        success(BatchMetadataRemoval(chatIds, folderId))
    }

    fun areFoldersExpanded(): Boolean =
        presentationPreferences.getBoolean(FOLDERS_EXPANDED_KEY, false)

    fun setFoldersExpanded(expanded: Boolean): Boolean =
        presentationPreferences.edit().putBoolean(FOLDERS_EXPANDED_KEY, expanded).commit()

    fun isFolderExpanded(folderId: String): Boolean =
        isUuid(folderId) && presentationPreferences.getBoolean(FOLDER_EXPANDED_PREFIX + folderId, false)

    fun setFolderExpanded(folderId: String, expanded: Boolean): Boolean =
        isUuid(folderId) && presentationPreferences.edit()
            .putBoolean(FOLDER_EXPANDED_PREFIX + folderId, expanded).commit()

    private fun <T> mutate(
        block: (
            ArrayList<HashMap<String, String>>,
            List<FolderRecord>
        ) -> MutationDecision<T>
    ): ChatNavigationResult<T> = synchronized(ChatPreferences.CHAT_LIST_LOCK) {
        val migration = migrateSchema()
        if (migration is ChatNavigationResult.Failure) return@synchronized migration
        val chats = readAuthoritativeChats() ?: return@synchronized unavailable()
        val folders = when (val read = readFolders()) {
            is FolderRead.Ok -> read.folders
            FolderRead.Corrupt -> return@synchronized failure(ChatNavigationFailure.CORRUPT_FOLDERS)
            FolderRead.Unsupported -> return@synchronized failure(ChatNavigationFailure.UNSUPPORTED_SCHEMA)
            else -> return@synchronized unavailable()
        }
        when (val decision = block(chats, folders)) {
            is MutationDecision.Fail -> failure(decision.reason)
            is MutationDecision.Write -> {
                if (!decision.writeChats && !decision.writeFolders) return@synchronized success(decision.value)
                val editor = chatListPreferences.edit()
                if (decision.writeChats) editor.putString("data", Gson().toJson(decision.chats))
                if (decision.writeFolders) editor.putString(FOLDERS_KEY, encodeFolders(decision.folders))
                if (editor.commit()) success(decision.value)
                else failure(ChatNavigationFailure.COMMIT_FAILED)
            }
        }
    }

    private fun readAuthoritativeChats(): ArrayList<HashMap<String, String>>? {
        val result = readChatList()
        return if (ChatStorageHealth.isAuthoritative(result.state)) result.chats else null
    }

    private sealed class FolderRead {
        data class Ok(val folders: List<FolderRecord>) : FolderRead()
        data object Missing : FolderRead()
        data object Corrupt : FolderRead()
        data object Unsupported : FolderRead()
        data object Unavailable : FolderRead()
    }

    private fun readFolders(): FolderRead {
        if (try { !chatListPreferences.contains(FOLDERS_KEY) } catch (_: Exception) { return FolderRead.Unavailable }) {
            return FolderRead.Missing
        }
        val raw = try { chatListPreferences.getString(FOLDERS_KEY, null) } catch (_: Exception) {
            return FolderRead.Unavailable
        } ?: return corruptFolders("null")
        return try {
            val root = JsonParser.parseString(raw).asJsonObject
            val version = root.get("version")?.asInt ?: return corruptFolders(raw)
            if (version > SCHEMA_VERSION) return FolderRead.Unsupported
            if (version != SCHEMA_VERSION) return corruptFolders(raw)
            val array = root.getAsJsonArray("folders") ?: return corruptFolders(raw)
            val folders = array.map { element ->
                val obj = element.asJsonObject
                FolderRecord(
                    id = obj.get("id")?.asString ?: throw IllegalArgumentException(),
                    name = obj.get("name")?.asString ?: throw IllegalArgumentException(),
                    pinned = obj.get("pinned")?.asBoolean ?: false
                )
            }
            val ids = folders.map { it.id }
            val normalizedNames = folders.map { it.name.trim().lowercase(Locale.ROOT) }
            if (folders.any { !isUuid(it.id) || it.name != it.name.trim() || it.name.isBlank() } ||
                ids.size != ids.toSet().size || normalizedNames.size != normalizedNames.toSet().size
            ) return corruptFolders(raw)
            FolderRead.Ok(folders)
        } catch (_: Exception) {
            corruptFolders(raw)
        }
    }

    private fun corruptFolders(raw: String): FolderRead {
        preserveCorruptFolders(raw)
        return FolderRead.Corrupt
    }

    private fun encodeFolders(folders: List<FolderRecord>): String =
        Gson().toJson(FolderCatalog(SCHEMA_VERSION, folders))

    private fun toNavigationItem(row: Map<String, String>) = ChatNavigationItem(
        id = ChatPreferences.storedChatId(row),
        name = row["name"].orEmpty(),
        timestamp = row["timestamp"]?.toLongOrNull() ?: 0L,
        pinned = row["pinned"] == "true",
        folderId = row[FOLDER_ID_KEY]?.takeIf { it.isNotBlank() }
    )

    private fun isUuid(value: String): Boolean = try {
        UUID.fromString(value).toString().equals(value, ignoreCase = true)
    } catch (_: Exception) {
        false
    }

    private sealed class MutationDecision<out T> {
        data class Fail(val reason: ChatNavigationFailure) : MutationDecision<Nothing>()
        data class Write<T>(
            val value: T,
            val chats: ArrayList<HashMap<String, String>>,
            val folders: List<FolderRecord>,
            val writeChats: Boolean,
            val writeFolders: Boolean
        ) : MutationDecision<T>()
    }

    private fun <T> success(value: T): ChatNavigationResult<T> = ChatNavigationResult.Success(value)
    private fun failure(reason: ChatNavigationFailure): ChatNavigationResult.Failure =
        ChatNavigationResult.Failure(reason)
    private fun unavailable(): ChatNavigationResult.Failure =
        failure(ChatNavigationFailure.STORAGE_UNAVAILABLE)
}
