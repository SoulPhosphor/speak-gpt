/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.includes

import android.content.Context
import java.io.File

/** App-owned, content-addressed PDF bytes and completed fallback caches. */
object PdfAttachmentStore {
    private const val ROOT = "chat_pdf_includes"
    private const val CACHE_ROOT = "pdf_fallback_cache"

    fun chatPdfsDir(context: Context, chatId: String): File {
        val root = context.getExternalFilesDir(ROOT) ?: File(context.filesDir, ROOT)
        return File(root, ImageImporter.sanitizeChatId(chatId)).apply { mkdirs() }
    }

    fun pdfFile(context: Context, chatId: String, hash: String?): File? =
        hash?.takeIf { it.isNotBlank() }?.let { File(chatPdfsDir(context, chatId), "$it.pdf") }

    fun pdfFile(context: Context, chatId: String, include: ChatInclude): File? =
        pdfFile(context, chatId, include.pdfFileHash)

    /** Cache is content-addressed within the owning chat, matching byte ownership. */
    fun fallbackFile(context: Context, chatId: String, hash: String): File =
        fallbackDir(context, chatId).resolve("$hash.txt")

    fun fallbackMetadataFile(context: Context, chatId: String, hash: String): File =
        fallbackDir(context, chatId).resolve("$hash.json")

    private fun fallbackDir(context: Context, chatId: String): File =
        File(File(context.filesDir, CACHE_ROOT), ImageImporter.sanitizeChatId(chatId)).apply { mkdirs() }

    fun deletePdfIfUnreferenced(
        context: Context,
        chatId: String,
        include: ChatInclude,
        stillReferenced: Boolean,
        fallbackStillReferenced: Boolean = stillReferenced
    ) {
        if (!stillReferenced) pdfFile(context, chatId, include)?.delete()
        val hash = include.pdfFileHash ?: return
        if (!fallbackStillReferenced) {
            fallbackFile(context, chatId, hash).delete()
            fallbackMetadataFile(context, chatId, hash).delete()
        }
    }

    fun deleteOrphanFile(file: File?) { file?.delete() }

    fun reconcileChatPdfs(context: Context, chatId: String, referencedHashes: Set<String>) {
        chatPdfsDir(context, chatId).listFiles()?.forEach { file ->
            if (file.extension == "pdf" && file.nameWithoutExtension !in referencedHashes) file.delete()
        }
    }

    fun deleteChatPdfs(context: Context, chatId: String): Boolean {
        val root = context.getExternalFilesDir(ROOT) ?: File(context.filesDir, ROOT)
        val dir = File(root, ImageImporter.sanitizeChatId(chatId))
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
        fallbackDir(context, chatId).deleteRecursively()
        return !dir.exists()
    }

    fun moveChatPdfs(context: Context, oldChatId: String, newChatId: String) {
        if (oldChatId == newChatId) return
        val root = context.getExternalFilesDir(ROOT) ?: File(context.filesDir, ROOT)
        val from = File(root, ImageImporter.sanitizeChatId(oldChatId))
        if (!from.exists()) return
        val to = File(root, ImageImporter.sanitizeChatId(newChatId)).apply { mkdirs() }
        from.listFiles()?.forEach { src ->
            val dst = File(to, src.name)
            if (!dst.exists() && !src.renameTo(dst)) src.copyTo(dst, overwrite = false)
            src.delete()
        }
        from.delete()
        val cacheRoot = File(context.filesDir, CACHE_ROOT)
        val oldCache = File(cacheRoot, ImageImporter.sanitizeChatId(oldChatId))
        val newCache = File(cacheRoot, ImageImporter.sanitizeChatId(newChatId))
        if (oldCache.exists() && !newCache.exists()) oldCache.renameTo(newCache)
    }

    fun replaceAllFromStaging(context: Context, stagedRoot: File): Boolean = try {
        require(stagedRoot.isDirectory)
        val root = context.getExternalFilesDir(ROOT) ?: File(context.filesDir, ROOT)
        if (root.exists() && !root.deleteRecursively()) return false
        root.mkdirs()
        stagedRoot.listFiles()?.filter(File::isDirectory)?.forEach { stagedChat ->
            val destination = File(root, stagedChat.name).apply { mkdirs() }
            stagedChat.listFiles()?.filter { it.isFile && it.extension == "pdf" }?.forEach { source ->
                source.copyTo(File(destination, source.name), overwrite = false)
            }
        }
        File(context.filesDir, CACHE_ROOT).deleteRecursively()
        true
    } catch (_: Exception) { false }
}
