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
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Copies and validates a PDF without extracting text or contacting a provider. */
object PdfImporter {
    const val MIME_TYPE = "application/pdf"
    const val MAX_SOURCE_BYTES = 256L * 1024L * 1024L

    /** The PDF header must appear within this many leading bytes (PDF 1.7 §7.5.2 practice). */
    internal const val HEADER_SEARCH_BYTES = 1024

    sealed class Result {
        data class Success(val include: ChatInclude, val onDiskFile: File) : Result()
        data class Unavailable(val fileName: String) : Result()
        data class Unreadable(val fileName: String) : Result()
        data class PasswordProtected(val fileName: String) : Result()
        data class Corrupt(val fileName: String) : Result()
        data class Empty(val fileName: String) : Result()
        data class TooLarge(val fileName: String) : Result()
        data class NotPdf(val fileName: String) : Result()
        /** App storage could not create, write or keep the private copy. */
        data class StorageLimit(val fileName: String) : Result()
        /** Reading the source stopped partway through. */
        data class InterruptedRead(val fileName: String) : Result()
        data class PermissionDenied(val fileName: String) : Result()
        data class FileGone(val fileName: String) : Result()
    }

    private class SourceReadFailure : Exception()
    private class StorageWriteFailure : Exception()

    fun isPdfSelection(context: Context, uri: Uri): Boolean {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (mime.equals(MIME_TYPE, true)) return true
        return displayName(context, uri).endsWith(".pdf", true)
    }

    fun import(context: Context, uri: Uri, chatId: String): Result {
        val name = displayName(context, uri)
        val dir: File
        val temp: File
        try {
            dir = PdfAttachmentStore.chatPdfsDir(context, chatId)
            temp = PdfAttachmentStore.createImportTemp(dir)
        } catch (_: Exception) {
            return Result.StorageLimit(name)
        }
        try {
            return importInto(context, uri, name, dir, temp)
        } finally {
            temp.delete()
            PdfAttachmentStore.releaseImportTemp(temp)
        }
    }

    private fun importInto(context: Context, uri: Uri, name: String, dir: File, temp: File): Result {
        var size = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return Result.Unavailable(name)
            input.use { source ->
                val output = try {
                    FileOutputStream(temp)
                } catch (_: IOException) {
                    throw StorageWriteFailure()
                }
                output.use {
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = try {
                            source.read(buffer)
                        } catch (_: IOException) {
                            throw SourceReadFailure()
                        }
                        if (count < 0) break
                        if (count == 0) continue
                        size += count
                        if (size > MAX_SOURCE_BYTES) return Result.TooLarge(name)
                        digest.update(buffer, 0, count)
                        try {
                            output.write(buffer, 0, count)
                        } catch (_: IOException) {
                            throw StorageWriteFailure()
                        }
                    }
                    try {
                        output.fd.sync()
                    } catch (_: IOException) {
                        throw StorageWriteFailure()
                    }
                }
            }
        } catch (_: SecurityException) {
            return Result.PermissionDenied(name)
        } catch (_: FileNotFoundException) {
            return Result.FileGone(name)
        } catch (_: SourceReadFailure) {
            return Result.InterruptedRead(name)
        } catch (_: StorageWriteFailure) {
            return Result.StorageLimit(name)
        } catch (_: Exception) {
            return Result.Unreadable(name)
        }
        if (size == 0L) return Result.Empty(name)
        if (!hasPdfSignature(temp)) return Result.NotPdf(name)
        val pageCount = try {
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { it.pageCount }
            }
        } catch (e: SecurityException) {
            return Result.PasswordProtected(name)
        } catch (e: IllegalArgumentException) {
            return if (e.message.orEmpty().contains("password", true)) {
                Result.PasswordProtected(name)
            } else Result.Corrupt(name)
        } catch (_: Exception) {
            return Result.Corrupt(name)
        }
        if (pageCount <= 0) return Result.Empty(name)
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val target = File(dir, "$hash.pdf")
        try {
            if (!target.exists() && !temp.renameTo(target)) {
                temp.copyTo(target, overwrite = false)
            }
        } catch (_: Exception) {
            return Result.StorageLimit(name)
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
        val header = ByteArray(HEADER_SEARCH_BYTES)
        val count = FileInputStream(file).use { input ->
            var total = 0
            while (total < header.size) {
                val read = input.read(header, total, header.size - total)
                if (read < 0) break
                total += read
            }
            total
        }
        return hasPdfHeader(header, count)
    }

    /**
     * True when "%PDF-" starts within the first [HEADER_SEARCH_BYTES] bytes.
     * Some scanners and mail systems put bytes before the header; readers
     * accept that, and the renderer below still rejects damaged files.
     */
    internal fun hasPdfHeader(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val marker = "%PDF-".toByteArray(Charsets.US_ASCII)
        val limit = minOf(length, HEADER_SEARCH_BYTES) - marker.size
        for (start in 0..limit) {
            if (marker.indices.all { bytes[start + it] == marker[it] }) return true
        }
        return false
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
