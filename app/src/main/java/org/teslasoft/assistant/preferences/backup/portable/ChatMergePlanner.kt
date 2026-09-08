package org.teslasoft.assistant.preferences.backup.portable

import java.util.Locale
import org.json.JSONArray
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationRepository

/** Pure preflight planner. A Ready result is still only staged data. */
object ChatMergePlanner {
    sealed class FolderResolution {
        data class MergeInto(val currentFolderId: String) : FolderResolution()
        data class CreateNew(val name: String) : FolderResolution()
    }

    data class FolderCollision(
        val backupFolder: PortableChatRestorePlan.FolderPlan,
        val currentFolder: PortableChatRestorePlan.FolderPlan
    )

    data class Report(
        val identicalSkipped: Int,
        val longerHistoriesUsed: Int,
        val conflicts: List<String>,
        val chatsAdded: Int,
        val foldersAdded: Int
    )

    sealed class Result {
        data class NeedsFolderDecisions(val collisions: List<FolderCollision>) : Result()
        data class Ready(val plan: PortableChatRestorePlan.Plan, val report: Report) : Result()
        data class Rejected(val detail: String) : Result()
    }

    fun plan(
        current: PortableChatRestorePlan.Plan,
        backup: PortableChatRestorePlan.Plan,
        folderResolutions: Map<String, FolderResolution> = emptyMap()
    ): Result {
        val currentById = current.chats.associateBy { it.chatId }
        val incoming = backup.chats.filter { it.chatId !in currentById }
        val neededBackupFolders = incoming.mapNotNullTo(LinkedHashSet()) {
            it.listRow[ChatNavigationRepository.FOLDER_ID_KEY]?.takeIf(String::isNotBlank)
        }
        val currentFoldersById = current.folders.associateBy { it.id }
        val currentFoldersByName = current.folders.associateBy { normalizeName(it.name) }
        val backupFoldersById = backup.folders.associateBy { it.id }

        val collisions = neededBackupFolders.mapNotNull { id ->
            val source = backupFoldersById[id] ?: return Rejected("chat folder $id has no definition")
            if (id in currentFoldersById) return@mapNotNull null
            currentFoldersByName[normalizeName(source.name)]?.let { FolderCollision(source, it) }
        }.filter { it.backupFolder.id !in folderResolutions }
        if (collisions.isNotEmpty()) return Result.NeedsFolderDecisions(collisions)

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

        val mergedChats = ArrayList(current.chats)
        var identicalSkipped = 0
        var longerHistoriesUsed = 0
        var chatsAdded = 0
        val conflicts = ArrayList<String>()
        for (source in backup.chats) {
            val existing = currentById[source.chatId]
            if (existing == null) {
                val row = LinkedHashMap(source.listRow)
                row[ChatNavigationRepository.FOLDER_ID_KEY]?.let { sourceFolderId ->
                    folderMap[sourceFolderId]?.let { row[ChatNavigationRepository.FOLDER_ID_KEY] = it }
                }
                mergedChats.add(source.copy(listRow = row))
                chatsAdded++
                continue
            }
            if (existing == source) {
                identicalSkipped++
                continue
            }
            val preferred = preferLongerHistory(existing, source)
            if (preferred != null) {
                longerHistoriesUsed++
                if (preferred === source) {
                    val position = mergedChats.indexOfFirst { it.chatId == existing.chatId }
                    mergedChats[position] = source.copy(listRow = existing.listRow)
                }
            } else {
                // Stable identity wins over backup content. Replace mode is the
                // explicit route for choosing the backup version.
                conflicts.add(source.chatId)
            }
        }

        return Result.Ready(
            PortableChatRestorePlan.Plan(
                chats = mergedChats,
                folders = mergedFolders,
                sourceFormat = ChatLogicalSerializer.FORMAT_V2
            ),
            Report(identicalSkipped, longerHistoriesUsed, conflicts, chatsAdded, foldersAdded)
        )
    }

    private fun preferLongerHistory(
        current: ChatLogicalImportPlan.ChatPlan,
        backup: ChatLogicalImportPlan.ChatPlan
    ): ChatLogicalImportPlan.ChatPlan? {
        if (contentRow(current.listRow) != contentRow(backup.listRow)) return null
        if (current.settings != backup.settings) return null
        val currentMessages = try { JSONArray(current.messagesJson) } catch (_: Exception) { return null }
        val backupMessages = try { JSONArray(backup.messagesJson) } catch (_: Exception) { return null }
        return when {
            isPrefix(currentMessages, backupMessages) -> backup
            isPrefix(backupMessages, currentMessages) -> current
            else -> null
        }
    }

    private fun isPrefix(shorter: JSONArray, longer: JSONArray): Boolean {
        if (shorter.length() >= longer.length()) return false
        for (index in 0 until shorter.length()) {
            if (shorter.get(index).toString() != longer.get(index).toString()) return false
        }
        return true
    }

    private fun contentRow(row: Map<String, String>): Map<String, String> =
        row.filterKeys { it !in ORGANIZATION_KEYS }

    private fun normalizeName(value: String): String = value.trim().lowercase(Locale.ROOT)

    private val ORGANIZATION_KEYS = setOf("timestamp", "pinned", ChatNavigationRepository.FOLDER_ID_KEY)
}
