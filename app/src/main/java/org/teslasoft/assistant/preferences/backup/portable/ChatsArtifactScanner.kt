/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.imagegen.GeneratedImageMetadata
import org.teslasoft.assistant.preferences.backup.portable.PortableChatRestorePlan.Reason
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationRepository
import org.teslasoft.assistant.preferences.includes.ChatInclude

/**
 * Reads a Recovery Backup chats artifact (v1, v2 or v3) one message at a time
 * and checks it with the same rules as [PortableChatRestorePlan.parse] (it
 * calls the same identity, message and settings checks), without ever holding
 * the whole artifact or a whole chat (chat-storage-and-backup-plan.md §4.5).
 *
 * It returns what the rest of the backup check needs: chat and message
 * counts, the live PDF attachments the package must carry, and the
 * generated-image references. Only one message, plus one chat's small header
 * and settings, is in memory at a time.
 *
 * One deliberate difference: `format` must come before `chats` (the identity
 * rules depend on it). Every artifact this app has written puts it first.
 */
internal object ChatsArtifactScanner {

    /** A live PDF attachment of one chat: where its bytes are and their size. */
    data class PdfRef(val chatId: String, val hash: String, val byteSize: Long)

    class Summary(
        val format: String,
        val chatCount: Int,
        val messageCount: Long,
        val pdfRefs: List<PdfRef>,
        val requiredPdfHashes: Set<String>,
        val imageIdsAll: Set<String>,
        val imageIdsRequired: Set<String>
    )

    sealed class Result {
        class Ok(val summary: Summary) : Result()

        /** A rule of [PortableChatRestorePlan] refused the artifact. */
        class Rejected(val reason: Reason, val detail: String) : Result()

        /** Not readable as a chats artifact, or its image references are unreadable. */
        class Unreadable(val detail: String) : Result()
    }

    private class ChatState(
        val digest: String,
        val settings: List<ChatLogicalImportPlan.SettingEntry>,
        val lastChanged: Long,
        val messageCount: Int,
        val pdfRefs: List<PdfRef>,
        val imageIdsAll: Set<String>,
        val imageIdsRequired: Set<String>
    )

    private class Stop(val result: Result) : Exception(null, null, false, false)

    fun scan(input: Reader): Result {
        val reader = JsonReader(input).apply { strictness = Strictness.LENIENT }
        return try {
            scanRoot(reader)
        } catch (stop: Stop) {
            stop.result
        } catch (e: Exception) {
            // The reader's path (for example $.chats[12].messages[40]) says
            // where reading stopped without quoting any content.
            Result.Rejected(
                Reason.MALFORMED,
                "the artifact is not readable JSON (${e.javaClass.simpleName} at ${runCatching { reader.path }.getOrDefault("?")})"
            )
        }
    }

    private fun scanRoot(reader: JsonReader): Result {
        var format: String? = null
        var complete: Boolean? = null
        var folders: JSONArray? = null
        var chatsSeen = false
        val byId = LinkedHashMap<String, ChatState>()
        val sourceReferencedFolderIds = LinkedHashSet<String>()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "format" -> format = orgValue(JsonParser.parseReader(reader)) as? String
                "complete" -> complete = orgValue(JsonParser.parseReader(reader)) as? Boolean
                "folders" -> folders = orgValue(JsonParser.parseReader(reader)) as? JSONArray
                "chats" -> {
                    val checkedFormat = checkFormat(format, complete)
                    if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                        throw Stop(Result.Rejected(Reason.MALFORMED, "no chats array"))
                    }
                    chatsSeen = true
                    reader.beginArray()
                    var index = 0
                    while (reader.hasNext()) {
                        scanChat(reader, index++, checkedFormat, byId, sourceReferencedFolderIds)
                    }
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val checkedFormat = checkFormat(format, complete)
        if (!chatsSeen) return Result.Rejected(Reason.MALFORMED, "no chats array")

        if (checkedFormat != ChatLogicalSerializer.FORMAT_V1) {
            val rawFolders = folders
                ?: return Result.Rejected(Reason.MALFORMED_FOLDERS, "no folders array")
            val decoded = ChatFolderPortableCodec.decodeArtifact(rawFolders)
                ?: return Result.Rejected(Reason.MALFORMED_FOLDERS, "folder definitions failed validation")
            val folderIds = decoded.mapTo(LinkedHashSet()) { it.id }
            val missing = sourceReferencedFolderIds - folderIds
            if (missing.isNotEmpty()) {
                return Result.Rejected(Reason.MISSING_FOLDER, "folder ${missing.first()} has no definition")
            }
            val unreferenced = folderIds - sourceReferencedFolderIds
            if (unreferenced.isNotEmpty()) {
                return Result.Rejected(
                    Reason.UNREFERENCED_FOLDER,
                    "folder ${unreferenced.first()} is not used by a backed-up chat"
                )
            }
        }

        val chats = byId.values
        val pdfRefs = chats.flatMap { it.pdfRefs }.distinct()
        return Result.Ok(
            Summary(
                format = checkedFormat,
                chatCount = chats.size,
                messageCount = chats.sumOf { it.messageCount.toLong() },
                pdfRefs = pdfRefs,
                requiredPdfHashes = pdfRefs.mapTo(LinkedHashSet()) { it.hash },
                imageIdsAll = chats.flatMapTo(LinkedHashSet()) { it.imageIdsAll },
                imageIdsRequired = chats.flatMapTo(LinkedHashSet()) { it.imageIdsRequired }
            )
        )
    }

    private fun checkFormat(format: String?, complete: Boolean?): String {
        if (format.isNullOrEmpty()) throw Stop(Result.Rejected(Reason.NOT_A_CHATS_ARTIFACT, "no format marker"))
        if (format !in PortableChatRestorePlan.SUPPORTED_FORMATS) {
            throw Stop(Result.Rejected(Reason.UNSUPPORTED_FORMAT, "format $format"))
        }
        if (complete == null) throw Stop(Result.Rejected(Reason.MALFORMED, "no completeness marker"))
        if (!complete) {
            throw Stop(Result.Rejected(Reason.INCOMPLETE_ARTIFACT, "the source marked this artifact incomplete"))
        }
        return format
    }

    private fun scanChat(
        reader: JsonReader,
        index: Int,
        format: String,
        byId: MutableMap<String, ChatState>,
        sourceReferencedFolderIds: MutableSet<String>
    ) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            throw Stop(Result.Rejected(Reason.MALFORMED, "entry $index is not an object"))
        }
        val header = JSONObject()
        var messagesSeen = false
        var messagesMalformed = false
        var messageRejection: PortableChatRestorePlan.Rejection? = null
        var messageCount = 0
        var lastMessageTime = 0L
        val messageIds = HashSet<String>()
        val digest = MessageDigest.getInstance("SHA-256")
        val pdfRefs = ArrayList<Pair<String, Long>>()
        val imageIdsAll = LinkedHashSet<String>()
        val imageIdsRequired = LinkedHashSet<String>()
        var imageProblem: String? = null

        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (name != "messages") {
                header.put(name, orgValue(JsonParser.parseReader(reader)) ?: JSONObject.NULL)
                continue
            }
            messagesSeen = true
            if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                messagesMalformed = true
                reader.skipValue()
                continue
            }
            reader.beginArray()
            while (reader.hasNext()) {
                val element = JsonParser.parseReader(reader)
                val label = header.optString("chat_id", "").ifEmpty { "entry $index" }
                val message = if (element.isJsonObject) {
                    try {
                        JSONObject(element.toString())
                    } catch (e: Exception) {
                        throw Stop(Result.Rejected(
                            Reason.MALFORMED,
                            "chat $label message at position $messageCount could not be read (${e.javaClass.simpleName})"
                        ))
                    }
                } else null
                if (messageRejection == null) {
                    messageRejection =
                        PortableChatRestorePlan.checkMessage(message, label, messageCount, messageIds)
                }
                // The same text JSONArray.toString() would give for this element,
                // so two aliases compare equal exactly when their histories do.
                if (messageCount > 0) digest.update(','.code.toByte())
                digest.update((message?.toString() ?: element.toString()).toByteArray(Charsets.UTF_8))
                if (message != null) {
                    lastMessageTime = maxOf(
                        lastMessageTime,
                        ChatMergePlanner.timestamp(message.opt(ChatMergePlanner.MESSAGE_MODIFIED_AT_KEY)) ?: 0L,
                        ChatMergePlanner.timestamp(message.opt("messageTime")) ?: 0L
                    )
                    livePdfs(message.optString("includes")).forEach(pdfRefs::add)
                    if (imageProblem == null) {
                        imageProblem = collectImage(message, imageIdsAll, imageIdsRequired)
                    }
                }
                messageCount++
            }
            reader.endArray()
        }
        reader.endObject()

        val identity = when (val result = PortableChatRestorePlan.planIdentity(header, index, format)) {
            is PortableChatRestorePlan.IdentityResult.Ok -> result
            is PortableChatRestorePlan.IdentityResult.Rejected ->
                throw Stop(Result.Rejected(result.reason, result.detail))
        }
        val chatId = identity.declaredId
        if (!messagesSeen || messagesMalformed) {
            throw Stop(Result.Rejected(Reason.MALFORMED, "chat $chatId has no messages array"))
        }
        messageRejection?.let { throw Stop(Result.Rejected(it.reason, it.detail)) }
        val settings = when (val result = PortableChatRestorePlan.planSettings(header, chatId)) {
            is PortableChatRestorePlan.SettingsResult.Ok -> result.settings
            is PortableChatRestorePlan.SettingsResult.Rejected ->
                throw Stop(Result.Rejected(result.reason, result.detail))
        }
        imageProblem?.let { throw Stop(Result.Unreadable(it)) }
        settings.firstOrNull { it.key == PENDING_INCLUDES_KEY }
            ?.value?.toString()?.let { livePdfs(it).forEach(pdfRefs::add) }

        val folderId = identity.listRow[ChatNavigationRepository.FOLDER_ID_KEY]?.takeIf(String::isNotBlank)
        folderId?.let(sourceReferencedFolderIds::add)
        val lastChanged = maxOf(
            ChatMergePlanner.lastChangedAt(
                ChatLogicalImportPlan.ChatPlan(chatId, identity.listRow, "[]", 0, emptyList())
            ),
            lastMessageTime
        )
        val state = ChatState(
            digest = digest.digest().joinToString("") { "%02x".format(it) },
            settings = settings,
            lastChanged = lastChanged,
            messageCount = messageCount,
            pdfRefs = pdfRefs.distinct().map { (hash, size) -> PdfRef(chatId, hash, size) },
            imageIdsAll = imageIdsAll,
            imageIdsRequired = imageIdsRequired
        )
        val previous = byId[chatId]
        if (previous == null) {
            byId[chatId] = state
            return
        }
        // Legacy chat-list rows can alias one immutable chat ID; consolidate
        // only when the ID-backed history and settings agree, as parse does.
        if (previous.digest != state.digest || previous.settings != state.settings) {
            throw Stop(
                Result.Rejected(
                    Reason.DUPLICATE_CHAT_ID,
                    "id $chatId appears twice with different history or settings"
                )
            )
        }
        if (state.lastChanged > previous.lastChanged) byId[chatId] = state
    }

    /** Hash and size of each live PDF in an includes value. */
    private fun livePdfs(includesJson: String): List<Pair<String, Long>> =
        ChatInclude.listFromJson(includesJson)
            .filter(ChatInclude::hasLivePdfBytes)
            .mapNotNull { include -> include.pdfFileHash?.let { it to include.pdfByteSize } }

    /** Records the message's generated image; returns a problem description, if any. */
    private fun collectImage(
        message: JSONObject,
        all: MutableSet<String>,
        required: MutableSet<String>
    ): String? {
        if (!message.has(GeneratedImageMetadata.KEY) || message.isNull(GeneratedImageMetadata.KEY)) return null
        val raw = message.opt(GeneratedImageMetadata.KEY)?.toString().orEmpty()
        val metadata = GeneratedImageMetadata.fromJson(raw)
            ?: return "a final chat has unreadable generated-image metadata"
        if (metadata.imageId.isBlank()) {
            return "a final chat has generated-image metadata without an image id"
        }
        all.add(metadata.imageId)
        if (metadata.status == GeneratedImageMetadata.STATUS_COMPLETE || !metadata.fileHash.isNullOrBlank()) {
            required.add(metadata.imageId)
        }
        return null
    }

    /** The value org.json would hold for [element] inside a parsed object. */
    private fun orgValue(element: JsonElement): Any? =
        JSONObject("{\"v\":$element}").opt("v")

    private const val PENDING_INCLUDES_KEY = "pending_includes"
}
