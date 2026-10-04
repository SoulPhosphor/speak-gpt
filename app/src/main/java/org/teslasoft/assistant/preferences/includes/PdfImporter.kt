/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.includes

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.teslasoft.assistant.util.Hash
import org.teslasoft.assistant.util.StableId
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/** Copies and validates a PDF without extracting text or contacting a provider. */
object PdfImporter {
    const val MIME_TYPE = "application/pdf"
    const val MAX_SOURCE_BYTES = 256L * 1024L * 1024L

    sealed class Result {
        data class Success(val include: ChatInclude, val onDiskFile: File) : Result()
        data class Unavailable(val fileName: String) : Result()
        data class Unreadable(val fileName: String) : Result()
        data class PasswordProtected(val fileName: String) : Result()
        data class Corrupt(val fileName: String) : Result()
        data class Empty(val fileName: String) : Result()
        data class TooLarge(val fileName: String) : Result()
        data class NotPdf(val fileName: String) : Result()
    }

    fun isPdfSelection(context: Context, uri: Uri): Boolean {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (mime.equals(MIME_TYPE, true)) return true
        return displayName(context, uri).endsWith(".pdf", true)
    }

    fun import(context: Context, uri: Uri, chatId: String): Result {
        val name = displayName(context, uri)
        val dir = PdfAttachmentStore.chatPdfsDir(context, chatId)
        val temp = File.createTempFile("pdf-import-", ".tmp", dir)
        var size = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return Result.Unavailable(name).also { temp.delete() }
            input.use { source ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        size += count
                        if (size > MAX_SOURCE_BYTES) return Result.TooLarge(name).also { temp.delete() }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
        } catch (_: SecurityException) {
            temp.delete(); return Result.Unavailable(name)
        } catch (_: Exception) {
            temp.delete(); return Result.Unreadable(name)
        }
        if (size == 0L) { temp.delete(); return Result.Empty(name) }
        if (!hasPdfSignature(temp)) { temp.delete(); return Result.NotPdf(name) }

        val pageCount = try {
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { it.pageCount }
            }
        } catch (e: SecurityException) {
            temp.delete(); return Result.PasswordProtected(name)
        } catch (e: IllegalArgumentException) {
            temp.delete()
            return if (e.message.orEmpty().contains("password", true)) {
                Result.PasswordProtected(name)
            } else Result.Corrupt(name)
        } catch (_: Exception) {
            temp.delete(); return Result.Corrupt(name)
        }
        if (pageCount <= 0) { temp.delete(); return Result.Empty(name) }

        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val target = File(dir, "$hash.pdf")
        try {
            if (target.exists()) temp.delete()
            else if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = false)
                temp.delete()
            }
        } catch (_: Exception) {
            temp.delete(); return Result.Unreadable(name)
        }
        return Result.Success(
            ChatInclude(
                id = StableId.newId("inc-"),
                fileName = name,
                kind = IncludeKind.PDF,
                form = IncludeForm.FULL,
                fullText = "",
                sourceFingerprint = Hash.hash(uri.toString()),
                pdfFileHash = hash,
                pdfMimeType = MIME_TYPE,
                pdfByteSize = size,
                pdfPageCount = pageCount
            ),
            target
        )
    }

    private fun hasPdfSignature(file: File): Boolean {
        val header = ByteArray(5)
        val count = FileInputStream(file).use { it.read(header) }
        return count == header.size && header.contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))
    }

    private fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "document.pdf"
    }
}
