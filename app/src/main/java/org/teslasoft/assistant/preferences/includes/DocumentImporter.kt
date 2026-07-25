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
import android.provider.OpenableColumns
import org.teslasoft.assistant.util.StableId
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream

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

    /** Hard ceiling on bytes read from disk, before any size guard. */
    private const val MAX_BYTES = 24 * 1024 * 1024

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

        /** Row 12: any other, unanticipated failure. */
        data class Unknown(val fileName: String) : Result()
    }

    /** MIME types offered to the system file picker. */
    val PICKER_MIME_TYPES = arrayOf(
        "text/plain",
        "text/markdown",
        "text/csv",
        "text/comma-separated-values",
        "application/csv",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    )

    fun import(context: Context, uri: Uri): Result {
        return try {
            importOrThrow(context, uri)
        } catch (_: Exception) {
            // A failure not already caught by a more specific case below —
            // row 12 of the approved mapping.
            Result.Unknown(displayNameSafely(context, uri))
        }
    }

    private fun importOrThrow(context: Context, uri: Uri): Result {
        val fileName = displayName(context, uri)
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val kindFromName = IncludeKind.fromFileName(fileName)
        if (extension.isNotEmpty() && kindFromName == null) {
            return Result.Unsupported(fileName)
        }
        val kind = kindFromName ?: try {
            kindFromMime(context.contentResolver.getType(uri))
        } catch (_: SecurityException) {
            return Result.PermissionDenied(fileName)
        } catch (_: Exception) {
            return openFailureResult(context, uri, fileName)
        } ?: return if (providerResolves(context, uri)) {
            Result.Unsupported(fileName)
        } else {
            Result.SourceUnavailable(fileName)
        }

        val inputStream = try {
            context.contentResolver.openInputStream(uri)
        } catch (_: SecurityException) {
            return Result.PermissionDenied(fileName)
        } catch (_: FileNotFoundException) {
            return openFailureResult(context, uri, fileName, fileMissing = true)
        } catch (_: Exception) {
            return openFailureResult(context, uri, fileName)
        }

        if (inputStream == null) return openFailureResult(context, uri, fileName)

        val text: String
        val sourceTruncated: Boolean
        var csvTotalRows: Int? = null

        if (kind == IncludeKind.DOCX) {
            val extracted = try {
                PushbackInputStream(inputStream, 1).use { stream ->
                    val firstByte = stream.read()
                    if (firstByte < 0) return Result.Empty(fileName)
                    stream.unread(firstByte)
                    DocxTextExtractor.extract(stream)
                }
            } catch (_: Exception) {
                return Result.InterruptedRead(fileName)
            }
            when (extracted) {
                is DocxTextExtractor.ExtractResult.Success -> {
                    text = extracted.text
                    sourceTruncated = extracted.sourceTruncated
                }
                DocxTextExtractor.ExtractResult.NotDocx ->
                    return Result.ContentMismatch(fileName)
                DocxTextExtractor.ExtractResult.PasswordProtected ->
                    return Result.PasswordProtected(fileName)
                DocxTextExtractor.ExtractResult.Corrupted ->
                    return Result.Corrupted(fileName)
            }
        } else {
            val capped = try {
                inputStream.use { it.readBytesCapped(MAX_BYTES) }
            } catch (_: Exception) {
                return Result.InterruptedRead(fileName)
            }
            if (capped.bytes.isEmpty()) return Result.Empty(fileName)

            text = DocumentTextDecoder.decode(
                bytes = capped.bytes,
                allowTruncatedTail = capped.sourceTruncated
            )
                ?: return Result.ContentMismatch(fileName)
            sourceTruncated = capped.sourceTruncated

            if (kind == IncludeKind.CSV && sourceTruncated) {
                csvTotalRows = try {
                    val countStream = context.contentResolver.openInputStream(uri)
                        ?: return Result.InterruptedRead(fileName)
                    countStream.use { stream ->
                        csvReader(stream).use { reader ->
                            IncludeTextPolicy.countCsvDataRows(reader)
                        }
                    }
                } catch (_: SecurityException) {
                    return Result.PermissionDenied(fileName)
                } catch (_: Exception) {
                    return Result.InterruptedRead(fileName)
                }
            }
        }

        if (text.isBlank()) return Result.Empty(fileName)
        // A .docx that parsed is text by construction; the guard is for files
        // claiming to be plain text that are really binary — indistinguishable
        // from a corrupted text file, so it shares ContentMismatch (row 10).
        if (kind != IncludeKind.DOCX && !IncludeTextPolicy.looksLikeText(text)) {
            return Result.ContentMismatch(fileName)
        }

        val sized = IncludeTextPolicy.applySizeGuard(
            text = text,
            kind = kind,
            sourceTruncated = sourceTruncated,
            csvTotalRows = csvTotalRows
        )
        return Result.Success(
            ChatInclude(
                id = StableId.newId("inc-"),
                fileName = fileName,
                kind = kind,
                form = IncludeForm.FULL,
                fullText = sized.text,
                notice = sized.notice
            )
        )
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

    /** Named distinctly from the stdlib's own InputStream.readBytes so the
     *  capped version can never be shadowed by (or resolve to) that one. */
    private data class CappedRead(
        val bytes: ByteArray,
        val sourceTruncated: Boolean
    )

    private fun InputStream.readBytesCapped(limit: Int): CappedRead {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (read == 0) continue
            val remaining = limit - total
            if (remaining <= 0) return CappedRead(out.toByteArray(), true)
            val kept = minOf(read, remaining)
            out.write(buffer, 0, kept)
            total += kept
            if (kept < read) return CappedRead(out.toByteArray(), true)
        }
        return CappedRead(out.toByteArray(), false)
    }

    private fun csvReader(input: InputStream): InputStreamReader {
        val stream = PushbackInputStream(input, 3)
        val prefix = ByteArray(3)
        var read = 0
        while (read < prefix.size) {
            val count = stream.read(prefix, read, prefix.size - read)
            if (count < 0) break
            if (count == 0) continue
            read += count
        }

        val (bomBytes, charset) = when {
            read >= 3 &&
                prefix[0] == 0xEF.toByte() &&
                prefix[1] == 0xBB.toByte() &&
                prefix[2] == 0xBF.toByte() -> 3 to Charsets.UTF_8
            read >= 2 &&
                prefix[0] == 0xFF.toByte() &&
                prefix[1] == 0xFE.toByte() -> 2 to Charsets.UTF_16LE
            read >= 2 &&
                prefix[0] == 0xFE.toByte() &&
                prefix[1] == 0xFF.toByte() -> 2 to Charsets.UTF_16BE
            else -> 0 to Charsets.UTF_8
        }

        if (read > bomBytes) {
            stream.unread(prefix, bomBytes, read - bomBytes)
        }
        return InputStreamReader(stream, charset)
    }
}
