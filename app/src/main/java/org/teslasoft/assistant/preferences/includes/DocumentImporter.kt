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

package org.teslasoft.assistant.preferences.includes

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.teslasoft.assistant.util.Hash
import org.teslasoft.assistant.util.StableId
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Turns a picked file into a [ChatInclude], on the device, with no network
 * and no third-party libraries.
 *
 * Everything the model receives for a document is plain text produced here.
 * That is what makes attachments work identically on every OpenAI-compatible
 * endpoint (GLM, DeepSeek, OpenRouter, …): there is no provider-specific file
 * upload anywhere in the app, so switching endpoints changes nothing about
 * how a document behaves.
 */
object DocumentImporter {

    /**
     * Every distinguishable attach failure, per the approved mapping
     * of detectable code conditions to messages (document-includes-plan.md).
     * Each case corresponds to exactly one row of that mapping; several rows
     * that are not distinguishable in code share one case on purpose (never
     * a falsely specific one per cause).
     */
    sealed class Result {
        data class Success(val include: ChatInclude) : Result()

        /** Row 1: extension/MIME not in the supported set. */
        data class Unsupported(val fileName: String) : Result()

        /** Row 2: SecurityException opening the file — permission expired
         *  or revoked. */
        data class PermissionDenied(val fileName: String) : Result()

        /** Row 3: open failed and the content provider does not resolve —
         *  the source app is gone or not responding. */
        data class SourceUnavailable(val fileName: String) : Result()

        /** Row 4: FileNotFoundException from a live content provider. */
        data class FileGone(val fileName: String) : Result()

        /** Row 5: the file opened, but reading it stopped part-way. */
        data class InterruptedRead(val fileName: String) : Result()

        /** Row 6: a .docx wrapped in an encrypted OLE2/CFB container. */
        data class PasswordProtected(val fileName: String) : Result()

        /** Rows 7/8/10: not a zip at all, a zip with no Word document part,
         *  or plain text whose bytes are not usable text — indistinguishable
         *  from each other in code, so one shared message. */
        data class ContentMismatch(val fileName: String) : Result()

        /** Row 9: a .docx whose document part was located but could not be
         *  read — positive evidence of a genuine, damaged Word file. */
        data class Corrupted(val fileName: String) : Result()

        /** Row 11: opened fine, but there was nothing in it. */
        data class Empty(val fileName: String) : Result()

        /**
         * The file has no bytes of its own and the app that owns it offered
         * no format this app can read. Distinct from [Unsupported]: the file
         * type is fine, the conversion is what is unavailable.
         */
        data class ExportUnavailable(val fileName: String) : Result()

        /**
         * The conversion was offered but did not produce anything — the
         * owning app is offline, signed out, or its export failed. Distinct
         * from every local read failure because the user's next step is
         * different: check the connection and the account, then retry.
         */
        data class ExportFailed(val fileName: String) : Result()

        /** The complete import could not fit its admitted retained heap. */
        data class DeviceMemoryLimit(val fileName: String) : Result()

        /** Office ZIP expansion exceeded the ratio or total-data guard. */
        data class ArchiveExpansionLimit(val fileName: String) : Result()

        /** App-private temporary storage could not preserve its reserve. */
        data class StorageLimit(val fileName: String) : Result()

        /** Row 12: any other, unanticipated failure. */
        data class Unknown(val fileName: String) : Result()
    }

    /**
     * MIME types offered to the system file picker.
     *
     * The last two are Google Docs and Google Sheets. They are listed by
     * their own Google-native types, not by the format they end up as: a
     * Google Doc identifies itself as a Google Doc, so filtering for .docx
     * alone would hide every one of them. Google Slides is deliberately
     * absent — this app has no presentation reader.
     */
    val PICKER_MIME_TYPES = arrayOf(
        "text/plain",
        "text/markdown",
        "text/csv",
        "text/comma-separated-values",
        "application/csv",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        GoogleExport.NATIVE_DOCUMENT,
        GoogleExport.NATIVE_SPREADSHEET
    )

    /** Opaque, stable pending-only identity for an Android document URI. */
    fun sourceFingerprint(sourceIdentity: String): String = Hash.hash(sourceIdentity)

    fun import(context: Context, uri: Uri): Result {
        val admission = ImportMemoryBudget.admission()
        if (!admission.canBegin) {
            return Result.DeviceMemoryLimit(displayNameSafely(context, uri))
        }
        return try {
            importOrThrow(
                context,
                uri,
                ImportMemoryBudget.fromAdmission(admission)
            )
        } catch (_: OutOfMemoryError) {
            // Defensive only. Admission and retained-allocation accounting are
            // the normal capacity tests; an OOM is never deliberately induced.
            Result.DeviceMemoryLimit(displayNameSafely(context, uri))
        } catch (_: Exception) {
            // A failure not already caught by a more specific case below —
            // row 12 of the approved mapping.
            Result.Unknown(displayNameSafely(context, uri))
        }
    }

    private fun importOrThrow(
        context: Context,
        uri: Uri,
        budget: ImportMemoryBudget
    ): Result {
        val rawName = displayName(context, uri)

        val sourceMime = try {
            context.contentResolver.getType(uri)
        } catch (_: SecurityException) {
            return Result.PermissionDenied(rawName)
        } catch (_: Exception) {
            return openFailureResult(context, uri, rawName)
        }

        // Google Slides, and any other Google-native type this app has no
        // reader for, is refused by name here. It is filtered out of the
        // picker already; this is the second line of defence so it can never
        // fail later with a vaguer reason.
        if (GoogleExport.isUnsupportedNative(sourceMime)) return Result.Unsupported(rawName)

        val export: GoogleExport.Export? = when {
            GoogleExport.isSupportedNative(sourceMime) -> {
                val offered = try {
                    context.contentResolver.getStreamTypes(uri, "*/*")
                } catch (_: SecurityException) {
                    return Result.PermissionDenied(rawName)
                } catch (_: Exception) {
                    null
                }
                GoogleExport.chooseExport(sourceMime, offered)
                    ?: return Result.ExportUnavailable(rawName)
            }
            // A file with no bytes of its own, from a provider whose
            // conversions this app does not read.
            isVirtualDocument(context, uri) -> return Result.ExportUnavailable(rawName)
            else -> null
        }

        val fileName = export?.let { GoogleExport.exportedFileName(rawName, it) } ?: rawName
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val kindFromName = IncludeKind.fromFileName(fileName)
        if (extension.isNotEmpty() && kindFromName == null) {
            return Result.Unsupported(fileName)
        }
        val kind = kindFromName
            ?: kindFromMime(sourceMime)
            ?: return if (providerResolves(context, uri)) {
                Result.Unsupported(fileName)
            } else {
                Result.SourceUnavailable(fileName)
            }

        val storage = ImportStorageGuard(context.cacheDir)
        val storageAtStart = storage.snapshot()
        if (storageAtStart.availableBytes <= storageAtStart.reserveBytes) {
            return Result.StorageLimit(fileName)
        }

        val copied = copySourceToPrivateFile(
            context = context,
            uri = uri,
            export = export,
            fileName = fileName,
            storage = storage,
            budget = budget
        )
        if (copied is SourceCopy.Failure) return copied.result
        val source = copied as SourceCopy.Success

        try {
            if (source.bytes == 0L) return Result.Empty(fileName)

            val text: String = when (kind) {
                IncludeKind.DOCX, IncludeKind.XLSX -> {
                    val expansion = OfficeArchiveExpansionGuard(
                        compressedSourceBytes = source.bytes,
                        maximumExpandedBytes = storage.snapshot().maximumExpandedBytes
                    )
                    val extracted = if (kind == IncludeKind.DOCX) {
                        CompleteOfficeTextExtractor.extractDocx(source.file, budget, expansion)
                    } else {
                        CompleteOfficeTextExtractor.extractXlsx(source.file, budget, expansion)
                    }
                    when (extracted) {
                        is CompleteOfficeTextExtractor.Result.Success -> extracted.text
                        CompleteOfficeTextExtractor.Result.NotOfficeDocument ->
                            return Result.ContentMismatch(fileName)
                        CompleteOfficeTextExtractor.Result.PasswordProtected ->
                            return Result.PasswordProtected(fileName)
                        CompleteOfficeTextExtractor.Result.Corrupted ->
                            return Result.Corrupted(fileName)
                        CompleteOfficeTextExtractor.Result.DeviceMemoryLimit ->
                            return Result.DeviceMemoryLimit(fileName)
                        CompleteOfficeTextExtractor.Result.ArchiveExpansionLimit ->
                            return Result.ArchiveExpansionLimit(fileName)
                    }
                }
                IncludeKind.TXT, IncludeKind.MARKDOWN, IncludeKind.CSV -> {
                    try {
                        decodeCompleteText(source.file, budget)
                            ?: return Result.ContentMismatch(fileName)
                    } catch (_: ImportMemoryBudget.LimitExceeded) {
                        return Result.DeviceMemoryLimit(fileName)
                    } catch (_: CharacterCodingException) {
                        return Result.ContentMismatch(fileName)
                    } catch (_: Exception) {
                        return readFailure(fileName, export != null)
                    }
                }
                IncludeKind.JPEG, IncludeKind.PNG ->
                    return Result.Unsupported(fileName)
            }

            if (text.isBlank()) return Result.Empty(fileName)
            // Office text is produced by XML parsing. The binary-content guard
            // remains for the three plain source formats.
            if (kind != IncludeKind.DOCX && kind != IncludeKind.XLSX &&
                !IncludeTextPolicy.looksLikeText(text)
            ) {
                return Result.ContentMismatch(fileName)
            }

            return Result.Success(
                ChatInclude(
                    id = StableId.newId("inc-"),
                    fileName = fileName,
                    kind = kind,
                    form = IncludeForm.FULL,
                    fullText = text,
                    notice = IncludeNotice.None,
                    sourceFingerprint = sourceFingerprint(uri.toString())
                )
            )
        } finally {
            // The attachment owns only extracted text. The byte-for-byte source
            // copy never survives an import, successful or failed.
            source.file.delete()
        }
    }

    private sealed class SourceCopy {
        data class Success(val file: File, val bytes: Long) : SourceCopy()
        data class Failure(val result: Result) : SourceCopy()
    }

    /**
     * One source read, including one Google conversion request. The parser
     * never reopens a virtual document for a second pass.
     */
    private fun copySourceToPrivateFile(
        context: Context,
        uri: Uri,
        export: GoogleExport.Export?,
        fileName: String,
        storage: ImportStorageGuard,
        budget: ImportMemoryBudget
    ): SourceCopy {
        val temp = try {
            storage.requireWrite(1)
            File.createTempFile("document-import-", ".source", context.cacheDir)
        } catch (_: ImportStorageGuard.LimitExceeded) {
            return SourceCopy.Failure(Result.StorageLimit(fileName))
        } catch (_: Exception) {
            return SourceCopy.Failure(Result.StorageLimit(fileName))
        }

        fun copy(input: InputStream, expectedLength: Long): SourceCopy {
            var total = 0L
            val copyBufferCharge = try {
                budget.claim((16 * 1024).toLong())
            } catch (_: ImportMemoryBudget.LimitExceeded) {
                input.close()
                temp.delete()
                return SourceCopy.Failure(Result.DeviceMemoryLimit(fileName))
            }
            return try {
                input.use { source ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            storage.requireWrite(read)
                            output.write(buffer, 0, read)
                            total += read.toLong()
                        }
                        output.fd.sync()
                    }
                }
                if (expectedLength >= 0 && total != expectedLength) {
                    temp.delete()
                    SourceCopy.Failure(readFailure(fileName, export != null))
                } else {
                    SourceCopy.Success(temp, total)
                }
            } catch (_: ImportStorageGuard.LimitExceeded) {
                temp.delete()
                SourceCopy.Failure(Result.StorageLimit(fileName))
            } catch (_: SecurityException) {
                temp.delete()
                SourceCopy.Failure(Result.PermissionDenied(fileName))
            } catch (_: OutOfMemoryError) {
                temp.delete()
                SourceCopy.Failure(Result.DeviceMemoryLimit(fileName))
            } catch (_: Exception) {
                temp.delete()
                SourceCopy.Failure(readFailure(fileName, export != null))
            } finally {
                copyBufferCharge.release()
            }
        }

        if (export != null) {
            return try {
                val descriptor = context.contentResolver
                    .openTypedAssetFileDescriptor(uri, export.mimeType, null)
                    ?: run {
                        temp.delete()
                        return SourceCopy.Failure(Result.ExportFailed(fileName))
                    }
                descriptor.use {
                    copy(it.createInputStream(), it.length)
                }
            } catch (_: SecurityException) {
                temp.delete()
                SourceCopy.Failure(Result.PermissionDenied(fileName))
            } catch (_: Exception) {
                temp.delete()
                SourceCopy.Failure(Result.ExportFailed(fileName))
            }
        }

        val expected = querySourceLength(context, uri)
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (_: SecurityException) {
            temp.delete()
            return SourceCopy.Failure(Result.PermissionDenied(fileName))
        } catch (_: FileNotFoundException) {
            temp.delete()
            return SourceCopy.Failure(
                openFailureResult(context, uri, fileName, fileMissing = true)
            )
        } catch (_: Exception) {
            temp.delete()
            return SourceCopy.Failure(openFailureResult(context, uri, fileName))
        }
        if (input == null) {
            temp.delete()
            return SourceCopy.Failure(openFailureResult(context, uri, fileName))
        }
        return copy(input, expected)
    }

    private fun querySourceLength(context: Context, uri: Uri): Long = try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                cursor.getLong(index)
            } else {
                -1L
            }
        } ?: -1L
    } catch (_: Exception) {
        -1L
    }

    private fun decodeCompleteText(
        file: File,
        budget: ImportMemoryBudget
    ): String? {
        val prefixCharge = budget.claim(3)
        try {
            val prefix = ByteArray(3)
            val prefixRead = file.inputStream().use { input ->
                var total = 0
                while (total < prefix.size) {
                    val read = input.read(prefix, total, prefix.size - total)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                }
                total
            }
            val charsetAndBom = when {
                prefixRead >= 3 &&
                    prefix[0] == 0xEF.toByte() &&
                    prefix[1] == 0xBB.toByte() &&
                    prefix[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
                prefixRead >= 2 &&
                    prefix[0] == 0xFF.toByte() &&
                    prefix[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
                prefixRead >= 2 &&
                    prefix[0] == 0xFE.toByte() &&
                    prefix[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
                else -> Charsets.UTF_8 to 0
            }

            return if (charsetAndBom.second > 0) {
                readDecodedFile(file, charsetAndBom.first, charsetAndBom.second, budget)
            } else {
                try {
                    readDecodedFile(file, Charsets.UTF_8, 0, budget)
                } catch (_: CharacterCodingException) {
                    readDecodedFile(file, Charset.forName("windows-1252"), 0, budget)
                }
            }
        } finally {
            prefixCharge.release()
        }
    }

    private fun readDecodedFile(
        file: File,
        charset: Charset,
        bomBytes: Int,
        budget: ImportMemoryBudget
    ): String {
        val parserCharge = budget.claim(48L * 1024L)
        val output = BudgetedTextBuilder(budget)
        try {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            file.inputStream().use { input ->
                var skipped = 0L
                while (skipped < bomBytes) {
                    val count = input.skip(bomBytes.toLong() - skipped)
                    if (count <= 0) {
                        if (input.read() < 0) break
                        skipped++
                    } else {
                        skipped += count
                    }
                }
                InputStreamReader(input, decoder).use { reader ->
                    val chars = CharArray(8 * 1024)
                    while (true) {
                        val read = reader.read(chars)
                        if (read < 0) break
                        if (read == 0) continue
                        output.append(chars, 0, read)
                    }
                }
            }
            return output.finish()
        } catch (e: OutOfMemoryError) {
            output.discard()
            throw e
        } catch (e: Exception) {
            output.discard()
            throw e
        } finally {
            parserCharge.release()
        }
    }

    /**
     * A read that stopped part-way means different things and needs
     * different advice depending on where the bytes were coming from: a
     * converted Google file was being produced over the network by another
     * app, an ordinary file was being read from storage.
     */
    private fun readFailure(fileName: String, exported: Boolean): Result =
        if (exported) Result.ExportFailed(fileName) else Result.InterruptedRead(fileName)

    /**
     * Whether this document has no byte representation of its own. Google
     * Docs and Sheets are the case this app handles; anything else virtual
     * is refused with a specific reason instead of a generic read failure.
     */
    private fun isVirtualDocument(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            if (index >= 0 && cursor.moveToFirst()) {
                val flags = cursor.getInt(index)
                flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0
            } else {
                false
            }
        } ?: false
    } catch (_: Exception) {
        false
    }

    /**
     * A missing provider identifies a source-app failure. A moved/deleted
     * file is only reported after FileNotFoundException; other failures from
     * a live provider stay generic rather than guessing.
     */
    private fun openFailureResult(
        context: Context,
        uri: Uri,
        fileName: String,
        fileMissing: Boolean = false
    ): Result =
        if (!providerResolves(context, uri)) {
            Result.SourceUnavailable(fileName)
        } else if (fileMissing) {
            Result.FileGone(fileName)
        } else {
            Result.Unknown(fileName)
        }

    private fun providerResolves(context: Context, uri: Uri): Boolean {
        val authority = uri.authority ?: return false
        return try {
            context.packageManager.resolveContentProvider(authority, 0) != null
        } catch (_: Exception) {
            false
        }
    }

    private fun displayNameSafely(context: Context, uri: Uri): String = try {
        displayName(context, uri)
    } catch (_: Exception) {
        "document"
    }

    private fun kindFromMime(mime: String?): IncludeKind? = when {
        mime == null -> null
        mime.equals("text/markdown", true) -> IncludeKind.MARKDOWN
        mime.contains("csv", true) -> IncludeKind.CSV
        mime.contains("wordprocessingml", true) -> IncludeKind.DOCX
        mime.contains("spreadsheetml", true) -> IncludeKind.XLSX
        mime.startsWith("text/") -> IncludeKind.TXT
        else -> null
    }

    private fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(index)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Exception) { /* fall through to the path-derived name */ }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "document"
    }

}
