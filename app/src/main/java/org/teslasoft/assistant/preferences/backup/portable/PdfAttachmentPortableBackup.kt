/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.includes.ChatInclude
import org.teslasoft.assistant.preferences.includes.PdfAttachmentStore
import java.io.File
import java.security.MessageDigest

/** Captures only original bytes still referenced by a live FULL PDF include. */
object PdfAttachmentPortableBackup {
    sealed class Result {
        data class Ok(val artifacts: List<PortablePackage.Artifact>) : Result()
        data class Failed(val detail: String) : Result()
    }

    fun buildArtifacts(context: Context, chatsJson: String): Result = try {
        val root = JSONObject(chatsJson)
        val chats = root.getJSONArray("chats")
        val artifacts = ArrayList<PortablePackage.Artifact>()
        val seen = HashSet<String>()
        for (index in 0 until chats.length()) {
            val chat = chats.getJSONObject(index)
            val chatId = chat.getString("chat_id")
            for (include in includes(chat)) {
                    if (!include.hasLivePdfBytes()) continue
                    val hash = include.pdfFileHash ?: return Result.Failed("live PDF has no hash")
                    val identity = "$chatId/$hash"
                    if (!seen.add(identity)) continue
                    val file = PdfAttachmentStore.pdfFile(context, chatId, include)
                        ?.takeIf { it.isFile } ?: return Result.Failed("live PDF bytes are missing")
                    if (file.length() != include.pdfByteSize || sha256(file) != hash) {
                        return Result.Failed("live PDF bytes failed validation")
                    }
                    artifacts += PortablePackage.Artifact(
                        entryName = "chat_pdfs/${safeChatId(chatId)}/$hash.pdf",
                        type = PortablePackage.TYPE_CHAT_PDF_ASSET,
                        file = file,
                        databaseKeyHex = null,
                        keySemantics = null,
                        schemaVersion = 1
                    )
            }
        }
        Result.Ok(artifacts)
    } catch (e: Exception) {
        Result.Failed(e.javaClass.simpleName)
    }

    fun prepareRestore(
        artifacts: List<PortablePackage.ValidatedArtifact>
    ): Map<String, File>? {
        val out = LinkedHashMap<String, File>()
        for (artifact in artifacts.filter { it.type == PortablePackage.TYPE_CHAT_PDF_ASSET }) {
            val hash = artifact.entryName.substringAfterLast('/').removeSuffix(".pdf")
            if (sha256(artifact.stagedFile) != hash) return null
            // The validated SHA-256 is the content identity. Avoid loading two
            // potentially large duplicate PDFs into memory just to compare them.
            out.putIfAbsent(hash, artifact.stagedFile)
        }
        return out
    }

    fun requiredHashes(plan: PortableChatRestorePlan.Plan): Set<String> = buildSet {
        plan.chats.forEach { chat ->
            includes(chat).forEach { include ->
                if (include.hasLivePdfBytes()) include.pdfFileHash?.let(::add)
            }
        }
    }

    fun includes(chat: ChatLogicalImportPlan.ChatPlan): List<ChatInclude> = buildList {
        val messages = JSONArray(chat.messagesJson)
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            addAll(ChatInclude.listFromJson(message.optString("includes")))
        }
        chat.settings.firstOrNull { it.key == "pending_includes" }
            ?.value?.toString()?.let { addAll(ChatInclude.listFromJson(it)) }
    }

    private fun includes(chat: JSONObject): List<ChatInclude> = buildList {
        val messages = chat.getJSONArray("messages")
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            addAll(ChatInclude.listFromJson(message.optString("includes")))
        }
        val settings = chat.optJSONArray("settings") ?: return@buildList
        for (index in 0 until settings.length()) {
            val entry = settings.optJSONObject(index) ?: continue
            if (entry.optString("k") == "pending_includes") {
                addAll(ChatInclude.listFromJson(entry.optString("v")))
            }
        }
    }

    private fun safeChatId(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
