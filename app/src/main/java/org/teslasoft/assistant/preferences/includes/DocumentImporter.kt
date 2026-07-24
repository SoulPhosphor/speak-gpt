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
import java.nio.charset.CharacterCodingException
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

    /** Hard ceiling on bytes read from disk, before any size guard. */
    private const val MAX_BYTES = 24 * 1024 * 1024

    /**
     * Every distinguishable attach failure, per the owner-approved mapping
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

        /** Row 4: open failed but the content provider resolves — the file
         *  itself is gone (moved or deleted). */
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
        val kind = IncludeKind.fromFileName(fileName)
            ?: kindFromMime(context.contentResolver.getType(uri))
            ?: return Result.Unsupported(fileName)

        val inputStream = try {
            context.contentResolver.openInputStream(uri)
        } catch (_: SecurityException) {
            return Result.PermissionDenied(fileName)
        } catch (_: Exception) {
            return openFailureResult(context, uri, fileName)
        }

        if (inputStream == null) return openFailureResult(context, uri, fileName)

        val bytes = try {
            inputStream.use { it.readBytesCapped(MAX_BYTES) }
        } catch (_: Exception) {
            // The file opened successfully; failing partway through reading
            // it is row 5, not an open failure.
            return Result.InterruptedRead(fileName)
        }

        if (bytes.isEmpty()) return Result.Empty(fileName)

        val text = when (kind) {
            IncludeKind.DOCX -> when (val extracted = DocxTextExtractor.extract(bytes)) {
                is DocxTextExtractor.ExtractResult.Success -> extracted.text
                DocxTextExtractor.ExtractResult.NotDocx -> return Result.ContentMismatch(fileName)
                DocxTextExtractor.ExtractResult.PasswordProtected -> return Result.PasswordProtected(fileName)
                DocxTextExtractor.ExtractResult.Corrupted -> return Result.Corrupted(fileName)
            }

            else -> decodeText(bytes) ?: return Result.ContentMismatch(fileName)
        }

        if (text.isBlank()) return Result.Empty(fileName)
        // A .docx that parsed is text by construction; the guard is for files
        // claiming to be plain text that are really binary — indistinguishable
        // from a corrupted text file, so it shares ContentMismatch (row 10).
        if (kind != IncludeKind.DOCX && !IncludeTextPolicy.looksLikeText(text)) {
            return Result.ContentMismatch(fileName)
        }

        val sized = IncludeTextPolicy.applySizeGuard(text, kind)
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
     * Distinguishes rows 3 and 4 for an open failure: if the content
     * provider itself resolves, the failure is the FILE (moved/deleted); if
     * it does not, the failure is the SOURCE APP (gone or not responding).
     */
    private fun openFailureResult(context: Context, uri: Uri, fileName: String): Result =
        if (providerResolves(context, uri)) {
            Result.FileGone(fileName)
        } else {
            Result.SourceUnavailable(fileName)
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

    /**
     * Decodes bytes as text. Strict UTF-8 first (the overwhelmingly common
     * case); a file that is not valid UTF-8 falls back to ISO-8859-1, which
     * cannot fail and keeps a Windows-era document readable rather than
     * turning it into replacement characters. Whichever path is taken, the
     * caller still runs the binary guard afterwards.
     */
    private fun decodeText(bytes: ByteArray): String? {
        val stripped = stripBom(bytes)
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(stripped)).toString()
        } catch (_: CharacterCodingException) {
            String(stripped, Charsets.ISO_8859_1)
        } catch (_: Exception) {
            null
        }
    }

    private fun stripBom(bytes: ByteArray): ByteArray {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return bytes.copyOfRange(3, bytes.size)
        }
        return bytes
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
    private fun java.io.InputStream.readBytesCapped(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
