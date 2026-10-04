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

    /** Cache is process-wide by content hash so duplicate PDFs never repeat OCR. */
    fun fallbackFile(context: Context, hash: String): File =
        File(context.filesDir, CACHE_ROOT).apply { mkdirs() }.resolve("$hash.txt")

    fun fallbackMetadataFile(context: Context, hash: String): File =
        File(context.filesDir, CACHE_ROOT).apply { mkdirs() }.resolve("$hash.json")

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
            fallbackFile(context, hash).delete()
            fallbackMetadataFile(context, hash).delete()
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
        if (!dir.exists()) return true
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
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
    }
}
