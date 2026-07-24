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

    sealed class Result {
        data class Success(val include: ChatInclude) : Result()

        /** The picked file's type is not one this app can read. */
        data class Unsupported(val fileName: String) : Result()

        /** Opened, but the contents are not usable text. */
        data class NotText(val fileName: String) : Result()

        /** Could not be opened or read at all. */
        data class Unreadable(val fileName: String) : Result()

        /** Opened fine, but there was nothing in it. */
        data class Empty(val fileName: String) : Result()
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
        val fileName = displayName(context, uri)
        val kind = IncludeKind.fromFileName(fileName)
            ?: kindFromMime(context.contentResolver.getType(uri))
            ?: return Result.Unsupported(fileName)

        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytesCapped(MAX_BYTES)
            } ?: return Result.Unreadable(fileName)
        } catch (_: Exception) {
            return Result.Unreadable(fileName)
        }

        if (bytes.isEmpty()) return Result.Empty(fileName)

        val text = when (kind) {
            IncludeKind.DOCX -> try {
                DocxTextExtractor.extract(bytes.inputStream())
            } catch (_: Exception) {
                null
            } ?: return Result.NotText(fileName)

            else -> decodeText(bytes) ?: return Result.NotText(fileName)
        }

        if (text.isBlank()) return Result.Empty(fileName)
        // A .docx that parsed is text by construction; the guard is for files
        // claiming to be plain text that are really binary.
        if (kind != IncludeKind.DOCX && !IncludeTextPolicy.looksLikeText(text)) {
            return Result.NotText(fileName)
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
