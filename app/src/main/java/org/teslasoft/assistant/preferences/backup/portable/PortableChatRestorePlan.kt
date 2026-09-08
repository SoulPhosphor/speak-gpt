package org.teslasoft.assistant.preferences.backup.portable

import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationRepository
import org.teslasoft.assistant.preferences.chatsearch.SearchableMessageProjection
import org.teslasoft.assistant.util.Hash

/**
 * Permanent read-only validation and staging plan for portable chat restore.
 * It accepts v1 and v2 without ever changing a chat's stable identity. No
 * storage is opened and no live data is mutated here.
 */
object PortableChatRestorePlan {
    data class FolderPlan(val id: String, val name: String, val pinned: Boolean)

    data class Plan(
        val chats: List<ChatLogicalImportPlan.ChatPlan>,
        val folders: List<FolderPlan>,
        val sourceFormat: String = ChatLogicalSerializer.FORMAT_V2,
        val duplicateRowsConsolidated: Int = 0
    ) {
        val chatCount: Int get() = chats.size
        val messageCount: Int get() = chats.sumOf { it.messageCount }
        val settingCount: Int get() = chats.sumOf { it.settings.size }
    }

    enum class Reason {
        NOT_A_CHATS_ARTIFACT,
        UNSUPPORTED_FORMAT,
        INCOMPLETE_ARTIFACT,
        MALFORMED,
        UNSAFE_CHAT_ID,
        IDENTITY_MISMATCH,
        DUPLICATE_CHAT_ID,
        MALFORMED_MESSAGE_ID,
        DUPLICATE_MESSAGE_ID,
        MALFORMED_FOLDERS,
        MISSING_FOLDER,
        UNREFERENCED_FOLDER,
        UNSUPPORTED_SETTING_TYPE,
        FORBIDDEN_CREDENTIAL
    }

    sealed class Result {
        data class Ok(val plan: Plan) : Result()
        data class Rejected(val reason: Reason, val detail: String) : Result()
    }

    fun parse(json: String): Result {
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            return rejected(Reason.MALFORMED, "the artifact is not readable JSON")
        }
        val format = root.optString("format", "")
        if (format.isEmpty()) return rejected(Reason.NOT_A_CHATS_ARTIFACT, "no format marker")
        if (format != ChatLogicalSerializer.FORMAT_V1 && format != ChatLogicalSerializer.FORMAT_V2) {
            return rejected(Reason.UNSUPPORTED_FORMAT, "format $format")
        }
        if (!root.has("complete")) return rejected(Reason.MALFORMED, "no completeness marker")
        if (!root.optBoolean("complete", false)) {
            return rejected(Reason.INCOMPLETE_ARTIFACT, "the source marked this artifact incomplete")
        }
        val entries = root.optJSONArray("chats")
            ?: return rejected(Reason.MALFORMED, "no chats array")

        val plans = ArrayList<ChatLogicalImportPlan.ChatPlan>(entries.length())
        val byId = LinkedHashMap<String, ChatLogicalImportPlan.ChatPlan>()
        val allMessageIds = HashSet<String>()
        var duplicateRows = 0
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index)
                ?: return rejected(Reason.MALFORMED, "entry $index is not an object")
            val validated = when (val result = planChat(entry, index, format)) {
                is ChatResult.Ok -> result.validated
                is ChatResult.Rejected -> return rejected(result.reason, result.detail)
            }
            val planned = validated.chat
            val previous = byId[planned.chatId]
            if (previous != null) {
                if (previous != planned) {
                    return rejected(
                        Reason.DUPLICATE_CHAT_ID,
                        "id ${planned.chatId} appears twice with different content"
                    )
                }
                duplicateRows++
                continue
            }
            for (messageId in validated.messageIds) {
                if (!allMessageIds.add(messageId)) {
                    return rejected(
                        Reason.DUPLICATE_MESSAGE_ID,
                        "message id $messageId appears more than once"
                    )
                }
            }
            byId[planned.chatId] = planned
            plans.add(planned)
        }

        val referencedFolderIds = plans.mapNotNullTo(LinkedHashSet()) {
            it.listRow[ChatNavigationRepository.FOLDER_ID_KEY]?.takeIf(String::isNotBlank)
        }
        val folders = if (format == ChatLogicalSerializer.FORMAT_V1) {
            emptyList()
        } else {
            val rawFolders = root.optJSONArray("folders")
                ?: return rejected(Reason.MALFORMED_FOLDERS, "no folders array")
            val decoded = ChatFolderPortableCodec.decodeArtifact(rawFolders)
                ?: return rejected(Reason.MALFORMED_FOLDERS, "folder definitions failed validation")
            val folderIds = decoded.mapTo(LinkedHashSet()) { it.id }
            val missing = referencedFolderIds - folderIds
            if (missing.isNotEmpty()) {
                return rejected(Reason.MISSING_FOLDER, "folder ${missing.first()} has no definition")
            }
            val unreferenced = folderIds - referencedFolderIds
            if (unreferenced.isNotEmpty()) {
                return rejected(
                    Reason.UNREFERENCED_FOLDER,
                    "folder ${unreferenced.first()} is not used by a backed-up chat"
                )
            }
            decoded.map { FolderPlan(it.id, it.name, it.pinned) }
        }
        return Result.Ok(Plan(plans, folders, format, duplicateRows))
    }

    private fun planChat(
        entry: JSONObject,
        index: Int,
        format: String
    ): ChatResult {
        val declaredId = entry.optString("chat_id", "")
        if (declaredId.isEmpty()) return chatRejected(Reason.MALFORMED, "entry $index has no chat id")
        if (!isStableChatId(declaredId)) {
            return chatRejected(Reason.UNSAFE_CHAT_ID, "entry $index has an invalid stable id")
        }

        val listRow = LinkedHashMap<String, String>()
        if (!entry.isNull("name")) listRow["name"] = entry.optString("name")
        val keys = entry.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.startsWith("list_")) listRow[key.removePrefix("list_")] = entry.optString(key)
        }
        val sourceResolvedId = listRow["id"] ?: Hash.hash(listRow["name"].toString())
        if (sourceResolvedId != declaredId) {
            return chatRejected(
                Reason.IDENTITY_MISMATCH,
                "entry $index rebuilds as $sourceResolvedId but was exported as $declaredId"
            )
        }
        listRow["id"] = declaredId
        if (format == ChatLogicalSerializer.FORMAT_V1) {
            // v1 never carried authoritative folder definitions. Preserve all
            // other fields, but never fabricate a folder from an unexplained ID.
            listRow.remove(ChatNavigationRepository.FOLDER_ID_KEY)
        }

        val messages = entry.optJSONArray("messages")
            ?: return chatRejected(Reason.MALFORMED, "chat $declaredId has no messages array")
        val messageIds = LinkedHashSet<String>()
        for (position in 0 until messages.length()) {
            val message = messages.optJSONObject(position)
                ?: return chatRejected(
                    Reason.MALFORMED,
                    "chat $declaredId has a malformed message at position $position"
                )
            if (!message.has(SearchableMessageProjection.MESSAGE_ID_KEY) ||
                message.isNull(SearchableMessageProjection.MESSAGE_ID_KEY)
            ) continue
            val messageId = message.optString(SearchableMessageProjection.MESSAGE_ID_KEY, "")
            if (!isCanonicalUuid(messageId)) {
                return chatRejected(
                    Reason.MALFORMED_MESSAGE_ID,
                    "chat $declaredId has an invalid message id at position $position"
                )
            }
            if (!messageIds.add(messageId)) {
                return chatRejected(
                    Reason.DUPLICATE_MESSAGE_ID,
                    "message id $messageId appears more than once"
                )
            }
        }

        val rawSettings = entry.optJSONArray("settings")
            ?: return chatRejected(Reason.MALFORMED, "chat $declaredId has no settings array")
        val settings = ArrayList<ChatLogicalImportPlan.SettingEntry>(rawSettings.length())
        val settingKeys = HashSet<String>()
        for (position in 0 until rawSettings.length()) {
            val raw = rawSettings.optJSONObject(position)
                ?: return chatRejected(Reason.MALFORMED, "chat $declaredId has a malformed setting")
            val key = raw.optString("k", "")
            if (key.isEmpty() || !settingKeys.add(key)) {
                return chatRejected(Reason.MALFORMED, "chat $declaredId has an invalid setting key")
            }
            if (key == "api_key") {
                return chatRejected(Reason.FORBIDDEN_CREDENTIAL, "chat $declaredId contains a credential")
            }
            val type = raw.optString("t", "")
            val value: Any = when (type) {
                "s" -> raw.optString("v")
                "b" -> raw.optBoolean("v")
                "i" -> raw.optInt("v")
                "l" -> raw.optLong("v")
                "f" -> raw.optDouble("v").toFloat()
                "ss" -> {
                    val array = raw.optJSONArray("v")
                        ?: return chatRejected(Reason.MALFORMED, "chat $declaredId has a malformed string set")
                    (0 until array.length()).mapTo(LinkedHashSet()) { array.optString(it) }
                }
                else -> return chatRejected(
                    Reason.UNSUPPORTED_SETTING_TYPE,
                    "chat $declaredId carries setting type '$type'"
                )
            }
            settings.add(ChatLogicalImportPlan.SettingEntry(key, type, value))
        }
        return ChatResult.Ok(
            ValidatedChat(
                ChatLogicalImportPlan.ChatPlan(
                declaredId,
                listRow,
                messages.toString(),
                messages.length(),
                settings
                ),
                messageIds
            )
        )
    }

    private fun isStableChatId(value: String): Boolean =
        isCanonicalUuid(value) || LEGACY_HASH.matches(value)

    private fun isCanonicalUuid(value: String): Boolean = try {
        UUID.fromString(value).toString().equals(value, ignoreCase = true)
    } catch (_: Exception) {
        false
    }

    private fun rejected(reason: Reason, detail: String) = Result.Rejected(reason, detail)
    private fun chatRejected(reason: Reason, detail: String) = ChatResult.Rejected(reason, detail)

    private sealed class ChatResult {
        data class Ok(val validated: ValidatedChat) : ChatResult()
        data class Rejected(val reason: Reason, val detail: String) : ChatResult()
    }

    private data class ValidatedChat(
        val chat: ChatLogicalImportPlan.ChatPlan,
        val messageIds: Set<String>
    )

    private val LEGACY_HASH = Regex("[0-9a-f]{64}")
}
