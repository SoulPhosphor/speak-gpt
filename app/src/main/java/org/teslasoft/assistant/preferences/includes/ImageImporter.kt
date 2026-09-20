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
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import org.teslasoft.assistant.util.Hash
import org.teslasoft.assistant.util.StableId
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Turns a picked image into a [ChatInclude], on the device, with no network.
 *
 * The user's original file is never touched. The importer decodes it once
 * (via [ImageDecoder], which handles JPEG/PNG/HEIC natively on API 28+),
 * applies EXIF orientation so the model sees a photo the right way up,
 * downsamples so the longest edge is at most [MAX_DIMENSION_PX], and writes
 * the normalized copy into the chat's own directory under a content-hash
 * file name. HEIC input is transcoded to JPEG so every downstream OpenAI-
 * compatible endpoint can read it. The reported width, height and MIME on
 * the returned [ChatInclude] describe the NORMALIZED image — the one that
 * will actually be sent.
 */
object ImageImporter {

    /** Longest-edge cap after downsampling. Documented as an app-level policy
     *  in `document-includes-plan.md`; not a universal provider limit. Fine
     *  detail and small text may be affected by the resize. */
    const val MAX_DIMENSION_PX = 2048

    /** JPEG encoder quality for downsampled or transcoded output. High enough
     *  that photos hold up well while still noticeably shrinking a 2048-cap
     *  copy of a phone photo. */
    private const val JPEG_QUALITY = 90

    /** Hard cap on the raw input bytes we will hold in memory during decode.
     *  Keeps a truly malformed or hostile file from OOMing the app before
     *  ImageDecoder even inspects it. */
    private const val MAX_SOURCE_BYTES = 64L * 1024L * 1024L

    /** MIME types offered to the system image picker. HEIC/HEIF are accepted
     *  and converted to JPEG at import time; any other input is refused
     *  immediately with the approved dialog. */
    val PICKER_MIME_TYPES = arrayOf(
        "image/jpeg",
        "image/jpg",
        "image/png",
        "image/heic",
        "image/heif"
    )

    sealed class Result {
        data class Success(val include: ChatInclude, val onDiskFile: File) : Result()
        /** Extension or MIME is not one of JPEG, PNG or HEIC. */
        data class Unsupported(val fileName: String) : Result()
        /** HEIC decode/encode failed. Distinct from a generic read failure
         *  because the user's next step is different (convert externally). */
        data class HeicConversionFailed(val fileName: String) : Result()
        /** The bytes could not be read at all — permission gone, source app
         *  unresponsive, or the file damaged. */
        data class ReadFailed(val fileName: String) : Result()
        /** The image would not fit the safe processing budget on this device. */
        data class TooLarge(val fileName: String) : Result()
        /** Any other, unanticipated failure. */
        data class Unknown(val fileName: String) : Result()
    }

    /** Stable pending-only identity for an Android image URI, mirroring
     *  [DocumentImporter.sourceFingerprint] so both share duplicate-detection
     *  semantics. */
    fun sourceFingerprint(sourceIdentity: String): String = Hash.hash(sourceIdentity)

    /** Absolute path to the chat's per-conversation image directory. Files
     *  under this path are the app's own copies of what the user attached;
     *  they belong to that chat and are removed when the chat is deleted. */
    fun chatImagesDir(context: Context, chatId: String): File {
        val root = context.getExternalFilesDir(CHAT_IMAGES_ROOT)
            ?: File(context.filesDir, CHAT_IMAGES_ROOT).apply { mkdirs() }
        val subDir = File(root, sanitizeChatId(chatId))
        if (!subDir.exists()) subDir.mkdirs()
        return subDir
    }

    /** Concrete on-disk file for a chat's stored image, or null when the
     *  bytes are gone (Reduced/Removed) and the caller should not try to
     *  resend them. */
    fun imageFile(context: Context, chatId: String, include: ChatInclude): File? {
        val hash = include.imageFileHash?.takeIf { it.isNotEmpty() } ?: return null
        val ext = extensionFor(include.imageMimeType) ?: return null
        return File(chatImagesDir(context, chatId), "$hash.$ext")
    }

    /** Delete the bytes-on-disk for one include. Called from Reduce and Remove
     *  so the user's copy leaves the device the moment it is no longer sent.
     *
     *  Files are content-hashed, so two includes can point at the same file.
     *  The delete is refused when any OTHER live FULL image include in the
     *  chat still references the same hash — passed in via [stillReferenced]
     *  so this stays a pure filesystem helper. The caller must have persisted
     *  the include's new (reduced/removed) state BEFORE calling this, so a
     *  crash mid-delete never leaves a saved FULL include pointing at bytes
     *  that are gone. */
    fun deleteImageFileIfUnreferenced(
        context: Context,
        chatId: String,
        include: ChatInclude,
        stillReferenced: Boolean
    ) {
        if (stillReferenced) return
        imageFile(context, chatId, include)?.takeIf { it.exists() }?.delete()
    }

    /** Delete a freshly written file whose include never got persisted (the
     *  activity went away between the disk write and the save). Safe to call
     *  with the [Result.Success.onDiskFile] directly. */
    fun deleteOrphanFile(file: File?) {
        file?.takeIf { it.exists() }?.delete()
    }

    /** Remove every image the chat ever stored. Called when a chat is deleted
     *  in full, so private copies cannot outlive their chat. */
    fun deleteChatImages(context: Context, chatId: String) {
        deleteChatImagesForDeletion(context, chatId)
    }

    /** Journal-aware form used by coordinated chat deletion. False keeps the
     * journal pending so an interrupted/failed filesystem cleanup is retried. */
    fun deleteChatImagesForDeletion(context: Context, chatId: String): Boolean {
        val root = context.getExternalFilesDir(CHAT_IMAGES_ROOT)
            ?: File(context.filesDir, CHAT_IMAGES_ROOT)
        val subDir = File(root, sanitizeChatId(chatId))
        if (!subDir.exists()) return true
        subDir.listFiles()?.forEach { it.delete() }
        subDir.delete()
        return !subDir.exists()
    }

    /**
     * Move a chat's image directory when its id changes (a rename derives a
     * new id from the new name). Called from the rename orchestration AFTER
     * the prefs transaction has flipped the chat-list pointer, so the include
     * JSON already lives under [newChatId]; this makes the hashes it
     * references resolvable again.
     *
     * `renameTo` is a single filesystem operation on the same volume (both
     * paths are under the app's external files dir), so it either moves the
     * whole directory or does nothing. On the rare failure it falls back to a
     * copy; whatever it cannot move is left for [reconcileChatImages] to clean
     * up, and a missing file surfaces as a normal "image unavailable" at send
     * rather than as corruption.
     */
    fun moveChatImages(context: Context, oldChatId: String, newChatId: String) {
        if (oldChatId == newChatId) return
        val root = context.getExternalFilesDir(CHAT_IMAGES_ROOT)
            ?: File(context.filesDir, CHAT_IMAGES_ROOT)
        val from = File(root, sanitizeChatId(oldChatId))
        if (!from.exists()) return
        val to = File(root, sanitizeChatId(newChatId))
        if (to.exists()) {
            // A prior partial move (or a name-hash collision with a deleted
            // chat) left a destination behind. Merge file-by-file; content
            // hashes make same-named files identical, so an existing file is
            // simply kept.
            from.listFiles()?.forEach { src ->
                val dst = File(to, src.name)
                if (!dst.exists()) {
                    if (!src.renameTo(dst)) src.copyTo(dst, overwrite = false)
                }
                src.delete()
            }
            from.delete()
            return
        }
        if (from.renameTo(to)) return
        // renameTo can fail across some provider-backed volumes; fall back to
        // a recursive copy and best-effort clear of the source.
        try {
            to.mkdirs()
            from.listFiles()?.forEach { src ->
                src.copyTo(File(to, src.name), overwrite = false)
            }
            from.listFiles()?.forEach { it.delete() }
            from.delete()
        } catch (_: Exception) {
            // Reconciliation cleans whatever is left; a missing image is a
            // normal, recoverable "image unavailable", never data corruption.
        }
    }

    /**
     * Delete any file in the chat's image directory not referenced by a live
     * FULL image include. [referencedHashes] is the set of `imageFileHash`
     * values still in use (pending + every saved message). Run on chat load
     * so an import that wrote a file but never persisted its include — the
     * activity died in the gap — does not leave bytes behind forever.
     */
    fun reconcileChatImages(
        context: Context,
        chatId: String,
        referencedHashes: Set<String>
    ) {
        val root = context.getExternalFilesDir(CHAT_IMAGES_ROOT) ?: return
        val subDir = File(root, sanitizeChatId(chatId))
        if (!subDir.exists()) return
        subDir.listFiles()?.forEach { file ->
            val hash = file.name.substringBeforeLast('.', "")
            if (hash.isNotEmpty() && hash !in referencedHashes) file.delete()
        }
    }

    /**
     * Import one picked image into the given chat.
     *
     * @param displayNameOverride when the caller has a better name than the
     *  provider offers — used by the Camera flow, which needs the app to name
     *  the photo after the chat, not the always-`tmp.jpg` placeholder file.
     */
    fun import(
        context: Context,
        uri: Uri,
        chatId: String,
        displayNameOverride: String? = null
    ): Result {
        return try {
            importOrThrow(context, uri, chatId, displayNameOverride)
        } catch (_: OutOfMemoryError) {
            Result.TooLarge(displayNameSafely(context, uri, displayNameOverride))
        } catch (_: Exception) {
            Result.Unknown(displayNameSafely(context, uri, displayNameOverride))
        }
    }

    private fun importOrThrow(
        context: Context,
        uri: Uri,
        chatId: String,
        displayNameOverride: String?
    ): Result {
        val rawName = displayNameOverride?.takeIf { it.isNotBlank() }
            ?: displayName(context, uri)

        val sourceMime = try {
            context.contentResolver.getType(uri)
        } catch (_: SecurityException) {
            return Result.ReadFailed(rawName)
        } catch (_: Exception) {
            null
        }

        val inputKind = classify(sourceMime, rawName)
            ?: return Result.Unsupported(rawName)

        val bytes = try {
            readAllBytes(context, uri)
        } catch (_: SecurityException) {
            return Result.ReadFailed(rawName)
        } catch (_: SizeExceeded) {
            return Result.TooLarge(rawName)
        } catch (_: Exception) {
            return Result.ReadFailed(rawName)
        }
        if (bytes.isEmpty()) return Result.ReadFailed(rawName)

        // ImageDecoder decodes JPEG/PNG/HEIC natively on API 28+, which is
        // this app's minSdk. A ByteBuffer source is stable across multiple
        // internal reads and avoids re-opening the provider URI mid-import.
        val decoded = try {
            ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
            ) { decoder, info, _ ->
                decoder.setTargetSampleSize(
                    computeSampleSize(info.size.width, info.size.height)
                )
                // A software bitmap is what BitmapFactory used to hand back,
                // and it is what Bitmap.compress() requires later. Hardware
                // bitmaps cannot be re-encoded.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (_: OutOfMemoryError) {
            return Result.TooLarge(rawName)
        } catch (_: Exception) {
            return if (inputKind == InputKind.HEIC) {
                Result.HeicConversionFailed(rawName)
            } else {
                Result.ReadFailed(rawName)
            }
        }

        // NOTE: ImageDecoder already honours EXIF orientation while decoding
        // (unlike BitmapFactory, which the profile-image code has to correct
        // by hand). Re-applying orientation here would rotate/mirror a second
        // time, so there is deliberately no EXIF step between decode and
        // downsample.
        val downsized = try {
            downsampleToCap(decoded)
        } catch (_: OutOfMemoryError) {
            decoded.recycle()
            return Result.TooLarge(rawName)
        }
        if (downsized !== decoded) decoded.recycle()

        val outputMime = when (inputKind) {
            InputKind.JPEG, InputKind.HEIC -> "image/jpeg"
            InputKind.PNG -> "image/png"
        }
        val outputExt = when (outputMime) {
            "image/png" -> "png"
            else -> "jpg"
        }

        val encoded = try {
            encode(downsized, outputMime)
        } catch (_: OutOfMemoryError) {
            downsized.recycle()
            return Result.TooLarge(rawName)
        } finally {
            downsized.recycle()
        }
        if (encoded == null) {
            return if (inputKind == InputKind.HEIC) {
                Result.HeicConversionFailed(rawName)
            } else {
                Result.Unknown(rawName)
            }
        }

        val hash = Hash.hash(encoded.bytes)
        val dir = chatImagesDir(context, chatId)
        val target = File(dir, "$hash.$outputExt")
        try {
            if (!target.exists()) {
                FileOutputStream(target).use { out -> out.write(encoded.bytes) }
            }
        } catch (_: OutOfMemoryError) {
            target.delete()
            return Result.TooLarge(rawName)
        } catch (_: Exception) {
            target.delete()
            return Result.Unknown(rawName)
        }

        // The display name for camera captures is chosen by the caller; a
        // gallery pick keeps its own name unless the extension needs to be
        // rewritten because HEIC was transcoded to JPEG.
        val displayName = when {
            inputKind == InputKind.HEIC -> rewriteExtension(rawName, "jpg")
            else -> rawName
        }

        val include = ChatInclude(
            id = StableId.newId("inc-"),
            fileName = displayName,
            kind = if (outputMime == "image/png") IncludeKind.PNG else IncludeKind.JPEG,
            form = IncludeForm.FULL,
            fullText = "",
            imageFileHash = hash,
            imageMimeType = outputMime,
            imageWidth = encoded.width,
            imageHeight = encoded.height,
            sourceFingerprint = sourceFingerprint(uri.toString())
        )
        return Result.Success(include, target)
    }

    // ---------------------------------------------------------------------

    private const val CHAT_IMAGES_ROOT = "chat_includes"

    /** Sanitizes a chat id for use as a directory name. Chat ids are already
     *  stable hashes in this app, but a null/empty string would collapse
     *  every chat's images into a single "no-chat" directory. */
    internal fun sanitizeChatId(chatId: String): String {
        val trimmed = chatId.trim()
        if (trimmed.isEmpty()) return "unassigned"
        val sanitized = trimmed.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return sanitized.ifEmpty { "unassigned" }
    }

    /** Encoded output bytes plus the transmitted dimensions. Returned per
     *  call so overlapping imports never share dimension state (a static
     *  holder would let one import read another's width/height). */
    private data class EncodedImage(val bytes: ByteArray, val width: Int, val height: Int)

    private fun encode(bitmap: Bitmap, mime: String): EncodedImage? {
        // Read dimensions BEFORE recycling; the estimator on the include uses
        // them and cannot re-derive them from bytes without a redecode.
        val width = bitmap.width
        val height = bitmap.height
        val output = ByteArrayOutputStream(width * height / 4)
        val format = if (mime == "image/png") {
            Bitmap.CompressFormat.PNG
        } else {
            Bitmap.CompressFormat.JPEG
        }
        val ok = bitmap.compress(format, JPEG_QUALITY, output)
        if (!ok) return null
        return EncodedImage(output.toByteArray(), width, height)
    }

    private fun downsampleToCap(bitmap: Bitmap): Bitmap {
        val longest = kotlin.math.max(bitmap.width, bitmap.height)
        if (longest <= MAX_DIMENSION_PX) return bitmap
        val scale = MAX_DIMENSION_PX.toFloat() / longest.toFloat()
        val newWidth = kotlin.math.max(1, (bitmap.width * scale).toInt())
        val newHeight = kotlin.math.max(1, (bitmap.height * scale).toInt())
        return bitmap.scale(newWidth, newHeight)
    }

    /** Two-power sample size chosen so the decoded bitmap is not wildly
     *  larger than the eventual 2048-cap output. Halves memory pressure for
     *  very large source photos. */
    internal fun computeSampleSize(sourceWidth: Int, sourceHeight: Int): Int {
        val longest = kotlin.math.max(sourceWidth, sourceHeight)
        if (longest <= MAX_DIMENSION_PX) return 1
        var sample = 1
        while (longest / (sample * 2) >= MAX_DIMENSION_PX) sample *= 2
        return sample
    }

    private class SizeExceeded : RuntimeException()

    private fun readAllBytes(context: Context, uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("provider returned null stream")
        return input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (total > MAX_SOURCE_BYTES) throw SizeExceeded()
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }

    internal enum class InputKind { JPEG, PNG, HEIC }

    /** Classifies the picked image by MIME first, falling back to the file
     *  name's extension when the provider offers no MIME (some content
     *  providers return null for stream types). */
    internal fun classify(mime: String?, fileName: String): InputKind? {
        val fromMime = when {
            mime == null -> null
            mime.equals("image/jpeg", true) -> InputKind.JPEG
            mime.equals("image/jpg", true) -> InputKind.JPEG
            mime.equals("image/png", true) -> InputKind.PNG
            mime.equals("image/heic", true) -> InputKind.HEIC
            mime.equals("image/heif", true) -> InputKind.HEIC
            else -> null
        }
        if (fromMime != null) return fromMime
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> InputKind.JPEG
            "png" -> InputKind.PNG
            "heic", "heif" -> InputKind.HEIC
            else -> null
        }
    }

    internal fun rewriteExtension(fileName: String, newExt: String): String {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        return "$base.$newExt"
    }

    private fun extensionFor(mime: String?): String? = when {
        mime == null -> null
        mime.equals("image/jpeg", true) || mime.equals("image/jpg", true) -> "jpg"
        mime.equals("image/png", true) -> "png"
        else -> null
    }

    private fun displayNameSafely(
        context: Context,
        uri: Uri,
        override: String?
    ): String {
        override?.takeIf { it.isNotBlank() }?.let { return it }
        return try { displayName(context, uri) } catch (_: Exception) { "image" }
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
        } catch (_: Exception) { /* fall through to a path-derived name */ }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "image"
    }
}

/** [Bitmap.scale] wrapper for cross-version compat — matches how the older
 *  profile-image code uses createScaledBitmap. */
private fun Bitmap.scale(w: Int, h: Int): Bitmap =
    Bitmap.createScaledBitmap(this, w, h, true)
