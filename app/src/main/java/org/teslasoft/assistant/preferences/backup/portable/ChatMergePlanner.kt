package org.teslasoft.assistant.preferences.backup.portable

import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationRepository

/** Pure preflight planner. A Ready result is still only staged data. */
object ChatMergePlanner {
    const val CONTENT_MODIFIED_AT_KEY = ChatPreferences.CHAT_CONTENT_MODIFIED_AT_KEY
    const val MESSAGE_MODIFIED_AT_KEY = "messageModifiedTime"

    data class Options(
        val alwaysUseNewer: Boolean = true,
        val keepBothWhenOlderHasMore: Boolean = false
    )

    sealed class FolderResolution {
        data class MergeInto(val currentFolderId: String) : FolderResolution()
        data class CreateNew(val name: String) : FolderResolution()
    }

    sealed class ChatResolution {
        data object KeepCurrent : ChatResolution()
        data object UseIncoming : ChatResolution()
        data object KeepBoth : ChatResolution()
    }

    data class FolderCollision(
        val backupFolder: PortableChatRestorePlan.FolderPlan,
        val currentFolder: PortableChatRestorePlan.FolderPlan
    )

    data class ChatCollision(
        val current: ChatLogicalImportPlan.ChatPlan,
        val incoming: ChatLogicalImportPlan.ChatPlan,
        val currentChangedAt: Long,
        val incomingChangedAt: Long
    )

    data class Report(
        val identicalSkipped: Int,
        val longerHistoriesUsed: Int,
        val conflicts: List<String>,
        val chatsAdded: Int,
        val foldersAdded: Int,
        val copiesKept: Int = 0
    )

    sealed class Result {
        data class NeedsChatDecisions(val collisions: List<ChatCollision>) : Result()
        data class NeedsFolderDecisions(val collisions: List<FolderCollision>) : Result()
        data class Ready(val plan: PortableChatRestorePlan.Plan, val report: Report) : Result()
        data class Rejected(val detail: String) : Result()
    }

    fun plan(
        current: PortableChatRestorePlan.Plan,
        backup: PortableChatRestorePlan.Plan,
        folderResolutions: Map<String, FolderResolution> = emptyMap(),
        chatResolutions: Map<String, ChatResolution> = emptyMap(),
        options: Options = Options()
    ): Result {
        val currentById = current.chats.associateBy { it.chatId }
        val usedIds = current.chats.mapTo(LinkedHashSet()) { it.chatId }
        val selectedIncoming = ArrayList<ChatLogicalImportPlan.ChatPlan>()
        val replacements = LinkedHashMap<String, ChatLogicalImportPlan.ChatPlan>()
        val conflicts = ArrayList<String>()
        val unresolved = ArrayList<ChatCollision>()
        var identicalSkipped = 0
        var longerHistoriesUsed = 0
        var copiesKept = 0

        for (source in backup.chats) {
            val existing = currentById[source.chatId]
            if (existing == null) {
                selectedIncoming.add(source)
                usedIds.add(source.chatId)
                continue
            }
            if (sameContent(existing, source)) {
                identicalSkipped++
                continue
            }

            val currentChangedAt = lastChangedAt(existing)
            val incomingChangedAt = lastChangedAt(source)
            val olderHasMore = when {
                incomingChangedAt > currentChangedAt -> existing.messageCount > source.messageCount
                currentChangedAt > incomingChangedAt -> source.messageCount > existing.messageCount
                else -> false
            }
            val choice = chatResolutions[source.chatId] ?: when {
                options.keepBothWhenOlderHasMore && olderHasMore -> ChatResolution.KeepBoth
                incomingChangedAt > currentChangedAt &&
                    (!olderHasMore || options.alwaysUseNewer) -> ChatResolution.UseIncoming
                currentChangedAt > incomingChangedAt -> ChatResolution.KeepCurrent
                else -> null
            }
            if (choice == null) {
                unresolved.add(ChatCollision(existing, source, currentChangedAt, incomingChangedAt))
                continue
            }

            when (choice) {
                ChatResolution.KeepCurrent -> conflicts.add(source.chatId)
                ChatResolution.UseIncoming -> {
                    replacements[source.chatId] = withCurrentOrganization(source, existing)
                    if (source.messageCount > existing.messageCount) longerHistoriesUsed++
                }
                ChatResolution.KeepBoth -> {
                    val copyId = allocateCopyId(source, usedIds)
                    val copyRow = LinkedHashMap(source.listRow).apply { put("id", copyId) }
                    selectedIncoming.add(source.copy(chatId = copyId, listRow = copyRow))
                    usedIds.add(copyId)
                    copiesKept++
                }
            }
        }
        if (unresolved.isNotEmpty()) return Result.NeedsChatDecisions(unresolved)

        val neededBackupFolders = selectedIncoming.mapNotNullTo(LinkedHashSet()) {
            it.listRow[ChatNavigationRepository.FOLDER_ID_KEY]?.takeIf(String::isNotBlank)
        }
        val currentFoldersById = current.folders.associateBy { it.id }
        val currentFoldersByName = current.folders.associateBy { normalizeName(it.name) }
        val backupFoldersById = backup.folders.associateBy { it.id }

        val folderCollisions = neededBackupFolders.mapNotNull { id ->
            val source = backupFoldersById[id]
                ?: return Result.Rejected("chat folder $id has no definition")
            if (id in currentFoldersById) return@mapNotNull null
            currentFoldersByName[normalizeName(source.name)]?.let { FolderCollision(source, it) }
        }.filter { it.backupFolder.id !in folderResolutions }
        if (folderCollisions.isNotEmpty()) return Result.NeedsFolderDecisions(folderCollisions)

        val folderMap = LinkedHashMap<String, String>()
        val mergedFolders = ArrayList(current.folders)
        var foldersAdded = 0
        for (sourceId in neededBackupFolders) {
            val source = backupFoldersById.getValue(sourceId)
            if (sourceId in currentFoldersById) {
                folderMap[sourceId] = sourceId
                continue
            }
            val sameName = currentFoldersByName[normalizeName(source.name)]
            if (sameName == null) {
                folderMap[sourceId] = sourceId
                mergedFolders.add(source)
                foldersAdded++
                continue
            }
            when (val resolution = folderResolutions[sourceId]) {
                is FolderResolution.MergeInto -> {
                    if (resolution.currentFolderId != sameName.id) {
                        return Result.Rejected("folder resolution does not match the named current folder")
                    }
                    folderMap[sourceId] = sameName.id
                }
                is FolderResolution.CreateNew -> {
                    val name = resolution.name.trim()
                    if (name.isBlank() || mergedFolders.any { normalizeName(it.name) == normalizeName(name) }) {
                        return Result.Rejected("the restored folder needs a different valid name")
                    }
                    folderMap[sourceId] = sourceId
                    mergedFolders.add(source.copy(name = name))
                    foldersAdded++
                }
                null -> return Result.NeedsFolderDecisions(listOf(FolderCollision(source, sameName)))
            }
        }

        val mergedChats = current.chats.mapTo(ArrayList()) { replacements[it.chatId] ?: it }
        for (source in selectedIncoming) {
            val row = LinkedHashMap(source.listRow)
            row[ChatNavigationRepository.FOLDER_ID_KEY]?.let { sourceFolderId ->
                folderMap[sourceFolderId]?.let { row[ChatNavigationRepository.FOLDER_ID_KEY] = it }
            }
            mergedChats.add(source.copy(listRow = row))
        }

        return Result.Ready(
            PortableChatRestorePlan.Plan(
                chats = mergedChats,
                folders = mergedFolders,
                sourceFormat = ChatLogicalSerializer.FORMAT_V2
            ),
            Report(
                identicalSkipped,
                longerHistoriesUsed,
                conflicts,
                selectedIncoming.size,
                foldersAdded,
                copiesKept
            )
        )
    }

    fun lastChangedAt(chat: ChatLogicalImportPlan.ChatPlan): Long {
        var latest = timestamp(chat.listRow[CONTENT_MODIFIED_AT_KEY])
            ?: timestamp(chat.listRow["timestamp"])
            ?: 0L
        val messages = try { JSONArray(chat.messagesJson) } catch (_: Exception) { return latest }
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            latest = maxOf(
                latest,
                timestamp(message.opt(MESSAGE_MODIFIED_AT_KEY)) ?: 0L,
                timestamp(message.opt("messageTime")) ?: 0L
            )
        }
        return latest
    }

    private fun withCurrentOrganization(
        incoming: ChatLogicalImportPlan.ChatPlan,
        current: ChatLogicalImportPlan.ChatPlan
    ): ChatLogicalImportPlan.ChatPlan {
        val row = LinkedHashMap(incoming.listRow)
        for (key in ORGANIZATION_KEYS) {
            current.listRow[key]?.let { row[key] = it } ?: row.remove(key)
        }
        return incoming.copy(listRow = row)
    }

    private fun allocateCopyId(
        incoming: ChatLogicalImportPlan.ChatPlan,
        usedIds: Set<String>
    ): String {
        repeat(100) { attempt ->
            val seed = "${incoming.chatId}\u0000${incoming.messagesJson}\u0000$attempt"
            val candidate = UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()
            if (candidate !in usedIds) return candidate
        }
        throw IllegalStateException("unable to allocate a chat copy UUID")
    }

    private fun sameContent(
        current: ChatLogicalImportPlan.ChatPlan,
        backup: ChatLogicalImportPlan.ChatPlan
    ): Boolean = contentRow(current.listRow) == contentRow(backup.listRow) &&
        current.settings == backup.settings &&
        current.messagesJson == backup.messagesJson

    private fun timestamp(value: Any?): Long? = when (value) {
        null, JSONObject.NULL -> null
        is Number -> value.toLong().takeIf { it >= 0L }
        else -> value.toString().toLongOrNull()?.takeIf { it >= 0L }
    }

    private fun contentRow(row: Map<String, String>): Map<String, String> =
        row.filterKeys { it !in COMPARISON_IGNORED_KEYS }

    private fun normalizeName(value: String): String = value.trim().lowercase(Locale.ROOT)

    private val ORGANIZATION_KEYS = setOf("pinned", ChatNavigationRepository.FOLDER_ID_KEY)
    private val COMPARISON_IGNORED_KEYS = ORGANIZATION_KEYS +
        setOf("timestamp", CONTENT_MODIFIED_AT_KEY)
}
